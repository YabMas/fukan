(ns fukan.cozo.refusal-paths-test
  "A refusal is attributed against what `q` COMPILED and worded in what was WRITTEN, on the three
   compile paths where those differ: a query input substituted into a clause, a negation inside a
   helper body, and a rule reached only inside a measure."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]))

(defstructure RpThing "A thing." {:rp-holds [:* RpThing]})
(RpThing rp-a)
(RpThing rp-b {:rp-holds [rp-a]})

(s/defrelation :rp-wrap "test relation: a vocabulary rule whose body calls a rule that does not exist"
  [?a ?b] [(RpAbsent ?a ?b)])

(defn- db [] (build/with-grammar (build/vars->cozo [#'rp-a #'rp-b]) nil))

(def ^:private rp-b-ref [:entity/id "fukan.cozo.refusal-paths-test/rp-b"])

(defn- says
  "What `query` is refused with, run with `inputs` after the db and the vocab rules plus `extra`;
   nil when it ran. Every refusal is held to the floor here: plain text, the engine's code named,
   no generated script and no compiled rule name."
  [query extra & inputs]
  (let [e (try (apply cq/q query (db) (into (vec (s/vocab-rules)) extra) inputs) nil
               (catch clojure.lang.ExceptionInfo e e))
        m (some-> e ex-message)]
    (is (some? m) (str "refused: " (pr-str query)))
    (when m
      (is (not (re-find #"\u001b|:=|\*t_|\br_[A-Za-z]|rp_" m)) m)
      (is (.contains m (str (:code (ex-data e)))) m))
    m))

(defn- names? [m & clauses] (every? #(.contains (str m) (pr-str %)) clauses))

(deftest a-query-input-is-substituted-before-attribution
  (let [m (says '[:find [?n ...] :in $ % ?nm :where (RpThing ?n) (RpNowhere ?n ?nm)] [] "rp-a")]
    (is (names? m '(RpNowhere ?n ?nm)) "the call is shown with the variable its author wrote"))
  (let [m (says '[:find [?n ...] :in $ % ?x :where (RpThing ?n) (not (rp-holds ?x _)) (not (RpThing ?z))]
                [] rp-b-ref)]
    (is (names? m '(not (RpThing ?z))))
    (is (not (names? m '(not (rp-holds ?x _)))) "a negation holding an input is safe and is not named")
    (is (re-find #"binds \?z before" m)))
  (let [m (says '[:find [?n ...] :in $ % ?g :where (path ?g [:+ :rp-nowhere] ?n)] [] rp-b-ref)]
    (is (names? m '(path ?g [:+ :rp-nowhere] ?n)))
    (is (.contains m "`rp-nowhere`") "the relation is named as written, not as compiled"))
  (let [m (says '[:find ?n ?m :in $ % ?rel :where (path ?n ?rel ?m)] [] :rp-nowhere)]
    (is (names? m '(path ?n ?rel ?m)) "an input that IS the relation: the path that called it is named")
    (is (.contains m "`rp-nowhere`"))))

(deftest a-negation-is-judged-in-the-helper-body-it-is-in
  (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (not (rp-holds ?n _))
                  (or-join [?n] (and (RpThing ?n) (not (RpThing ?m))) (rp-holds ?n _))] [])]
    (is (names? m '(not (RpThing ?m))))
    (is (not (names? m '(not (rp-holds ?n _))))))
  (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (not-join [?n] (rp-holds ?n ?x) (not (RpThing ?z)))] [])]
    (is (names? m '(not (RpThing ?z))))
    (is (re-find #"binds \?z before" m)))
  (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (RpThing ?m)
                  (or-join [?n] (and (RpThing ?n) (not (rp-holds ?m _))))] [])]
    (is (names? m '(not (rp-holds ?m _))) "bound outside the helper is not bound inside it")))

(deftest a-rule-reached-only-inside-a-measure-is-attributed
  (let [m (says '[:find ?n ?c :in $ % :where (RpThing ?n) (measure ?c (count ?m) (RpNowhere ?n ?m))] [])]
    (is (names? m '(RpNowhere ?n ?m))))
  (let [m (says '[:find ?n ?c :in $ % :where (RpThing ?n) (measure ?c (count ?m) (rp-broken ?n ?m))]
                '[[(rp-broken ?a ?b) (RpMissing ?a ?b)]])]
    (is (names? m '(RpMissing ?a ?b)) "the clause inside the supplied rule"))
  (let [m (says '[:find ?n ?c :in $ :where (RpThing ?n) (measure ?c (count ?m) (rp-wrap ?n ?m))] [])]
    (is (names? m '(RpAbsent ?a ?b)) "the clause inside a VOCABULARY rule no `%` supplied"))
  (let [m (says '[:find ?n ?c :in $ % :where (RpThing ?n) (measure ?c (count ?m) (rp-holds ?n ?m) (not (RpThing ?z)))] [])]
    (is (names? m '(not (RpThing ?z))))))

(deftest a-literal-value-is-not-read-as-a-clause
  (let [m (says '[:find [?n ...] :in $ % ?v :where [?n :entity/name ?v] (RpNowhere ?n)] []
                '(not-join [1] (RpThing ?n)))]
    (is (names? m '(RpNowhere ?n)) "an input holding clause-shaped data is a value, not a clause"))
  (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (RpNowhere ?n [1 2]) [?n :entity/name {:k [3]}]] [])]
    (is (names? m '(RpNowhere ?n [1 2])) "a rule call's literal argument is not descended into")))

(deftest a-literal-equal-to-a-clause-does-not-take-its-place
  (let [m (says '[:find [?n ...] :in $ % ?v :where (RpThing ?n) (RpNowhere ?n) [?n :entity/name ?v]] []
                '(RpNowhere ?n))]
    (is (names? m '(RpNowhere ?n)) "the calling clause, not the input whose value equals it")
    (is (not (re-find #"\?v" m))))
  (let [m (says '[:find [?n ...] :in $ % ?v :where (RpThing ?n) (not (RpThing ?z)) [?n :entity/name ?v]] []
                '(not (RpThing ?z)))]
    (is (names? m '(not (RpThing ?z))) "the unsafe negation, though an input's value equals it")
    (is (re-find #"binds \?z before" m))))

(deftest an-inlined-rule-is-judged-where-it-was-inlined
  ;; A vocabulary rule that is ONLY a negation: unsafe as a rule of its own, safe once inlined where
  ;; its caller binds the variable. Held to this test — registered for good it would be emitted, and
  ;; refused, in every query handed the vocabulary as `%`.
  (let [vocab s/vocab-rules
        cache @#'cq/vocab-index-cache
        e (with-redefs [s/vocab-rules (fn [] (conj (vec (vocab)) '[(rp-unheld ?x) (not (rp-holds ?x _))]))]
            (reset! cache nil)
            (try (cq/q '[:find [?n ...] :in $ :where (RpThing ?n) (rp-unheld ?n) (not (RpThing ?z))] (db)) nil
                 (catch clojure.lang.ExceptionInfo e e)
                 (finally (reset! cache nil))))
        m (some-> e ex-message)]
    (is (re-find #"eval::unsafe_negation" (str m)))
    (is (names? m '(not (RpThing ?z))))
    (is (not (names? m '(not (rp-holds ?x _)))) "its caller binds ?n, so the inlined negation is safe")
    (is (re-find #"binds \?z before" (str m)))))

(deftest a-path-is-expanded-under-the-names-the-engine-was-handed
  ;; `q` expands the where WHOLE, naming a path's intermediate variables by the clause's position:
  ;; this path, second, binds `?_path1_1` — so a negation over `?_path0_1` has nothing binding it.
  (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (path ?n [:cat :rp-holds :rp-holds] ?m)
                  (not (RpThing ?_path0_1)) (not (RpThing ?z))] [])]
    (is (names? m '(not (RpThing ?_path0_1)) '(not (RpThing ?z))) "both unsafe negations")
    (is (re-find #"binds \?_path0_1, \?z before" m))))

(deftest a-negation-is-judged-as-the-engine-judges-it
  (let [unsafe '(not (RpThing ?m))
        beside (fn [safe] (says (vec (concat '[:find [?n ...] :in $ % :where (RpThing ?n)] [safe unsafe])) []))]
    (doseq [[why safe] '[["a value written in place among a call's arguments" (not (rp-holds ?z 12345))]
                         ["a negated triple, which reads a stored relation"   (not [?z :entity/name ?w])]]]
      (let [m (beside safe)]
        (is (names? m unsafe) why)
        (is (not (names? m safe)) why)
        (is (re-find #"binds \?m before" m) why))))
  (testing "two spellings the engine folds to one variable are one variable"
    (let [m (says '[:find [?a-b ...] :in $ % :where (RpThing ?a-b) (not (rp-holds ?a_b _)) (not (RpThing ?m))] [])]
      (is (names? m '(not (RpThing ?m))))
      (is (not (names? m '(not (rp-holds ?a_b _))))))))

(deftest a-refusal-is-a-sentence-even-when-naming-the-clause-fails
  (with-redefs-fn {#'cq/compiled-scopes (fn [& _] (throw (IllegalStateException. "attribution broke")))}
    (fn []
      (let [m (says '[:find [?n ...] :in $ % :where (RpThing ?n) (not (RpThing ?m))] [])]
        (is (re-find #"^The engine refused this query \(eval::unsafe_negation\): " m))
        (is (not (re-find #"attribution broke" m)))))))
