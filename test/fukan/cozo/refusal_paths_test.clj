(ns fukan.cozo.refusal-paths-test
  "A refusal is attributed against what `q` COMPILED and worded in what was WRITTEN, on the three
   compile paths where those differ: a query input substituted into a clause, a negation inside a
   helper body, and a rule reached only inside a measure."
  (:require [clojure.test :refer [deftest is]]
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
