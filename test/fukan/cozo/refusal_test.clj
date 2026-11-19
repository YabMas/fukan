(ns fukan.cozo.refusal-test
  "A query the ENGINE refuses leaves `q` as a sentence its author can act on: plain text, the
   engine's error code, and the clause as it was written — never the generated script the engine
   reported against."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]))

(defstructure RfThing "A thing." {})
(defstructure RfOther "Another." {})

(RfThing rf-a)
(RfOther rf-b)

(defn- db [] (build/with-grammar (build/vars->cozo [#'rf-a #'rf-b]) nil))

(defn- refused
  "The exception `where` is refused with — `extra` rules supplied as `%` beside the vocabulary's."
  ([where] (refused where []))
  ([where extra]
   (try (cq/q (vec (concat '[:find [?n ...] :in $ % :where] where)) (db)
              (into (vec (s/vocab-rules)) extra))
        nil
        (catch clojure.lang.ExceptionInfo e e))))

(defn- plain? [message]
  (and (not (re-find #"\u001b" message))
       (not (re-find #":=|\*t_str|r_Rf" message))))

(deftest a-call-to-a-missing-rule-names-its-clause
  (let [e (refused '[(or (RfThing ?n) (RfOther ?n))])
        m (ex-message e)]
    (is (plain? m))
    (is (re-find #"eval::rule_not_found" m))
    (is (re-find #"\(or \(RfThing \?n\) \(RfOther \?n\)\)" m) "the clause as written")
    (is (re-find #"or-join" m))
    (is (= "eval::rule_not_found" (:code (ex-data e)))))
  (testing "a missing rule that is not `or` gets no advice about alternatives"
    (let [m (ex-message (refused '[(RfThing ?n) (RfNowhere ?n)]))]
      (is (re-find #"\(RfNowhere \?n\)" m))
      (is (not (re-find #"or-join" m))))))

(deftest an-unsafe-negation-names-what-was-negated
  (let [m (ex-message (refused '[(not (RfThing ?n))]))]
    (is (plain? m))
    (is (re-find #"eval::unsafe_negation" m))
    (is (re-find #"\(not \(RfThing \?n\)\)" m))
    (is (re-find #"positive clause that binds \?n" m)))
  (testing "of two negations, the one nothing binds is the one named"
    (let [m (ex-message (refused '[(RfThing ?n) (not (RfOther ?n)) (not (RfOther ?m))]))]
      (is (re-find #"\(not \(RfOther \?m\)\)" m))
      (is (not (re-find #"\(not \(RfOther \?n\)\)" m)))
      (is (re-find #"binds \?m before" m)))))

(deftest a-refused-clause-inside-a-supplied-rule-is-named
  (testing "a missing rule called from a `%` rule body"
    (let [m (ex-message (refused '[(rf-wrap ?n)] '[[(rf-wrap ?n) (RfNowhere ?n)]]))]
      (is (plain? m))
      (is (re-find #"\(RfNowhere \?n\)" m))))
  (testing "an `or` inside a `%` rule body still gets the or-join advice"
    (let [m (ex-message (refused '[(rf-wrap ?n)] '[[(rf-wrap ?n) (or (RfThing ?n) (RfOther ?n))]]))]
      (is (re-find #"\(or \(RfThing \?n\) \(RfOther \?n\)\)" m))
      (is (re-find #"or-join" m))))
  (testing "an unsafe negation inside a `%` rule body, judged against that body alone"
    (let [m (ex-message (refused '[(RfThing ?n) (rf-neg ?n)]
                                 '[[(rf-neg ?n) (RfThing ?n) (not (RfOther ?m))]]))]
      (is (re-find #"\(not \(RfOther \?m\)\)" m))
      (is (re-find #"binds \?m before" m)))))

(deftest a-missing-relation-in-a-path-names-the-path
  (let [m (ex-message (refused '[(RfThing ?n) (path ?n :rf-nowhere ?m)]))]
    (is (plain? m))
    (is (re-find #"\(path \?n :rf-nowhere \?m\)" m))))

(deftest every-path-form-names-the-path
  (doseq [path '[(path ?n rf-nowhere ?m) (path ?n [:+ :rf-nowhere] ?m) (path ?n [:cat :child :rf-nowhere] ?m)]]
    (let [m (ex-message (refused (conj '[(RfThing ?n)] path)))]
      (is (plain? m) (pr-str path))
      (is (.contains m (pr-str path)) (pr-str path)))))

(deftest a-helper-body-is-its-own-scope
  (let [m (ex-message (refused '[(RfThing ?n)
                                 (not (RfOther ?missing))
                                 (not-join [?n] (RfThing ?n) (RfThing ?local) (not (RfOther ?local)))]))]
    (is (re-find #"eval::unsafe_negation" m))
    (is (.contains m "(not (RfOther ?missing))") "the unsafe negation is named")
    (is (not (.contains m "(not (RfOther ?local))")) "the safe one inside the helper is not")
    (is (re-find #"binds \?missing before" m))))

(deftest a-negated-or-join-is-named-by-its-join-vars
  (doseq [nj '[(not (or-join [?m] (RfThing ?m) (RfOther ?m)))
               (not (or-join [?m] [?m :entity/name "x"] [?m :entity/name "y"]))]]
    (let [m (ex-message (refused (vector '(RfThing ?n) nj)))]
      (is (plain? m))
      (is (re-find #"eval::unsafe_negation" m))
      (is (.contains m (str "nothing binds those of " (pr-str nj) ".")) "that negation is named")
      (is (re-find #"binds \?m before" m))
      (is (not (re-find #"`_` binds nothing" m)) "no wildcard advice for a negation holding none"))))

(deftest a-negated-triple-read-through-a-union-helper-is-named
  (testing "an attribute no bucket holds compiles to a helper call, which an unbound negation of is unsafe"
    (let [nj '(not [?z :unseen/value ?w])
          m  (ex-message (refused (vector '(RfThing ?n) nj)))]
      (is (plain? m))
      (is (re-find #"eval::unsafe_negation" m))
      (is (.contains m (str "nothing binds those of " (pr-str nj) ".")) "that negation is named")
      (is (re-find #"binds \?w, \?z before" m))
      (is (not (re-find #"`_` binds nothing" m))))))

(deftest a-negation-of-literals-alone-is-never-named
  (testing "a negation that holds no variable and no wildcard needs nothing bound"
    (let [ground '(not (RfOther "literal"))
          m      (ex-message (refused (vector '(RfThing ?n) ground '(not (RfOther ?missing)))))]
      (is (re-find #"eval::unsafe_negation" m))
      (is (.contains m "(not (RfOther ?missing))"))
      (is (not (.contains m (pr-str ground)))))))

(deftest a-negation-holding-only-a-wildcard-is-the-one-named
  (let [m (ex-message (refused '[(RfThing ?n) (not (RfOther _))]))]
    (is (plain? m))
    (is (re-find #"eval::unsafe_negation" m))
    (is (.contains m "nothing binds those of (not (RfOther _)).") "that negation and no other")
    (is (re-find #"`_` binds nothing" m))))

(deftest any-other-refusal-is-still-plain
  (let [m (ex-message (refused '[(RfThing ?m)]))]
    (is (plain? m))
    (is (re-find #"eval::unbound_symb_in_head" m))))

(deftest an-answerable-query-is-untouched
  (is (= 1 (count (cq/q '[:find [?n ...] :in $ % :where (RfThing ?n)] (db) (s/vocab-rules))))))

(deftest a-literal-collection-holds-no-variable
  (testing "a `?name` inside a literal value is data: it neither binds nor needs binding"
    (let [m (ex-message (refused '[(RfThing ?n) [?n :entity/name [?z]]
                                   (not (RfOther [?phantom])) (not (RfOther ?z))]))]
      (is (re-find #"eval::unsafe_negation" m))
      (is (.contains m "nothing binds those of (not (RfOther ?z)).") "the unsafe negation, and no other")
      (is (re-find #"binds \?z before" m))
      (is (not (.contains m "?phantom"))))))

(deftest a-triple-with-a-list-subject-keeps-its-three-terms
  (testing "a triple whose subject is a literal list is no predicate: the list is one term, and
            the `?z` inside it binds nothing"
    (let [m (ex-message (refused '[(RfThing ?n) [(literal ?z) :entity/name ?n] (not (RfOther ?z))]))]
      (is (re-find #"eval::unsafe_negation" m))
      (is (.contains m "nothing binds those of (not (RfOther ?z))."))
      (is (re-find #"binds \?z before" m)))))
