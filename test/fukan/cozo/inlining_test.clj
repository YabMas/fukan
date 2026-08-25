(ns fukan.cozo.inlining-test
  "The query compiler INLINES single-definition vocab rules (a view is a rule, and a materialized
   rule carries no key) and re-orients each expansion against what is already bound. Both halves are
   measured in `fukan.cozo.query`; these lock the two ways the transform can go silently wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            [fukan.common]))

(defn- db []
  (build/maps->cozo [{:entity/id "a" :structure/of :x/T :entity/name "a"}
                     {:entity/id "b" :structure/of :x/T :entity/name "b" :val/name "b-alt"}
                     {:entity/id "c" :structure/of :x/T :entity/name "c" :val/extracted true}]
                    []))

(deftest inlining-preserves-the-rule-it-replaces
  (testing "a query through the inlined `named` rule returns exactly what the direct datom does"
    (let [d (db)]
      (is (= #{"a" "b" "c"}
             (set (cq/q '[:find [?n ...] :where (named ?e ?n)] d))
             (set (cq/q '[:find [?n ...] :where [?e :entity/name ?n]] d)))
          "fold/unfold is semantics-preserving"))))

(deftest a-caller-supplied-rule-is-never-bypassed-by-inlining
  (testing "a `%` rule REDEFINING a vocab-rule name gives that name two definitions in scope, so it
            stops being a view and the call reaches the caller's rule. Inlining the vocab body
            instead would silently drop the caller's contribution — which is exactly what a test
            fixture's `:pair` slot did to a `%`-supplied `pair`."
    (is (contains? (set (cq/q '[:find [?n ...] :in $ % :where (named ?e ?n)]
                              (db)
                              '[[(named ?e ?n) [?e :val/name ?n]]]))
                   "b-alt")
        "the caller's extra definition contributes rows the vocab rule cannot produce")))

(deftest passing-the-vocab-rules-through-still-inlines
  (testing "the readings hand `q` the WHOLE vocab rule set as `%`; those dedup against the vocab's
            own copies, so they are pass-through, not redefinition, and inlining still applies.
            Excluding every caller-supplied NAME instead of every redefined DEFINITION turned
            inlining off for exactly the queries it was built for — brian's ns-graph went 2.5s → 259s
            — so this asserts the LOWERING, which the answer alone cannot distinguish."
    (let [d (db)
          body (second (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(named ?e ?n)] (s/vocab-rules) (cq/vocab-index) '[?n])))]
      (is (re-find #"t_str\[e, 'entity/name', n\]" body)
          (str "`named` should be inlined to its datom even with the vocab rules passed as %; got: " body))
      (is (not (re-find #"r_named\[" body))
          (str "…and therefore should NOT be left as a rule call; got: " body)))))

(deftest a-negation-inside-an-expansion-still-filters
  (testing "an inlined body is RE-ORDERED, and a `not` in it can land ahead of the clause binding
            its vars — here the negation mentions a var bound outside the expansion (?p) and one
            bound inside it (?e), so it outscores that clause. It must still filter correctly:
            Cozo binds by analysis over the whole body, not by position."
    (is (= #{"a" "b"}
           (set (cq/q '[:find [?n ...] :in $ %
                        :where [?y :val/extracted ?p] (named-unless ?p ?n)]
                      (db)
                      '[[(named-unless ?p ?n)
                         [?e :entity/name ?n]
                         (not [?e :val/extracted ?p])]])))
        "the extracted node is excluded wherever the ordering put the negation")))
