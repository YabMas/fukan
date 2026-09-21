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

(deftest a-predicate-inside-an-expansion-is-not-hoisted-above-its-binder
  (testing "a `not` may be re-ordered ahead of what binds it, but a PREDICATE may not: it compiles
            to an expression, and a registered predicate PORT compiles to a CozoScript function
            call. Evaluated before its argument is bound, Cozo may fail outright — the query ERRORS
            rather than answering wrong, and `check` then reports the law as undecidable. That is
            what a band's membership relation hit when it derived a namespace's band from its path
            with clojure.string/starts-with?:

              starts_with(i6_2, i6_1), at_val_value[i6_0, i6_1]
              x Evaluation of expression failed
              help: 'starts_with' requires strings or bytes

            Cozo's tolerance for a late binder is not uniform — the same shape over a stored
            relation answers fine — so this asserts the LOWERING rather than the answer, like
            `passing-the-vocab-rules-through-still-inlines` above. The hoist needs a var bound
            ENTERING the expansion, or the initial all-zero tie-break keeps written order and
            hides it: with ?n already bound, the predicate scores 1 while `[?y :val/name ?v]` —
            the only clause that can bind ?v — scores 0."
    (let [d    (db)
          body (second (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[[?x :entity/name ?n] (prefix-of ?n ?v)]
                                          '[[(prefix-of ?n ?v)
                                             [?e :entity/name ?n]
                                             [(clojure.string/starts-with? ?v ?n)]
                                             [?y :val/name ?v]]]
                                          (cq/vocab-index) '[?n])))
          pred (.indexOf ^String body "starts_with(")
          bind (.indexOf ^String body "'val/name'")]
      (is (pos? pred) (str "the predicate should be emitted at all; got: " body))
      (is (< bind pred)
          (str "the clause binding ?v must precede the predicate reading it; got: " body)))))

(deftest a-filtered-generator-is-not-inlined
  (testing "a single-definition rule whose body filters on a var the head does NOT expose is a
            GENERATOR with a selectivity, not a view: its cross product is what the filter cuts
            down, and folding it lifts that product inside whatever join the call site sits in.
            On brian a band's `in-band` — 121 prefixes × 904 namespaces cut to 1,070 rows —
            inlined twice into a 3,273-edge join cost 27.1s against 4.1s left standing. The
            ANSWER is identical either way, so this asserts the LOWERING."
    (let [d    (db)
          body (second (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(gen ?a ?b)]
                                          '[[(gen ?a ?b)
                                             [?a :entity/name ?n]
                                             [?b :val/name ?p]
                                             [(clojure.string/starts-with? ?n ?p)]]]
                                          (cq/vocab-index) '[?a ?b])))]
      (is (re-find #"r_gen\[" body)
          (str "the generator should be left as a rule call; got: " body))
      (is (not (re-find #"starts_with" body))
          (str "…so its filter should not appear in the calling body; got: " body)))))

(deftest a-predicate-over-head-vars-only-still-inlines
  (testing "the exclusion is about INTERIOR filtering, not about predicates. A rule filtering only
            on vars its head exposes is a genuine view — the caller sees exactly the same rows —
            and folding it is the win the whole transform exists for."
    (let [d    (db)
          body (second (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(pairish ?a ?b)]
                                          '[[(pairish ?a ?b)
                                             [?a :entity/name ?n] [?b :entity/name ?n]
                                             [(not= ?a ?b)]]]
                                          (cq/vocab-index) '[?a ?b])))]
      (is (not (re-find #"r_pairish\[" body))
          (str "a head-var-only filter should still inline; got: " body))
      (is (re-find #"t_str\[a, 'entity/name'" body)
          (str "…to its datoms; got: " body)))))

(deftest an-emitted-rule-body-is-oriented-against-its-head-vars
  (testing "a rule that survives to be emitted is one the compiler could not fold, and Cozo runs
            such a call under the caller's bindings — so the head args are what a call site can be
            expected to bind, and a body opening on a clause that constrains neither of them opens
            on an unconstrained scan, repeated per binding. Measured on brian: the `fulfils`
            derivation, authored leading with a `[?r :rel/from _]`, cost 175.4s as the second
            definition of the namespace-dependency graph and 0.09s with the clause binding its
            first head var moved to the front — the same 27 rows.

            Asserts the LOWERING: a multi-definition rule (a union — which is what makes it
            un-inlinable) whose first definition is authored scan-first."
    (let [d     (db)
          lines (first (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(ful ?a ?b)]
                                          '[[(ful ?a ?b)
                                             [?r :rel/from ?p] [?r :rel/kind :satisfier] [?r :rel/to ?a]
                                             [?s :rel/from ?p] [?s :rel/kind :surface] [?s :rel/to ?b]]
                                            [(ful ?a ?b)
                                             [?r :rel/from ?a] [?r :rel/kind :fulfils] [?r :rel/to ?b]]]
                                          (cq/vocab-index) '[?a ?b])))
          satisfier (first (filter #(re-find #"satisfier" %) lines))]
      (is satisfier "precondition: the union is emitted as rules, not inlined")
      (is (re-find #":= \*t_int\[r, 'rel/to', a\]" satisfier)
          (str "the clause binding the head var must lead; got: " satisfier)))))

(deftest a-predicate-is-not-hoisted-by-the-head-var-orientation
  (testing "orienting against the head vars must not treat them as BOUND: the caller's binding is
            Cozo's business, and a predicate emitted ahead of what this body binds fails outright.
            `a != b` mentions only head vars, so scoring alone would put it first."
    (let [d     (db)
          lines (first (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(pairq ?a ?b)]
                                          '[[(pairq ?a ?b) [?a :entity/name ?n] [?b :entity/name ?n] [(not= ?a ?b)]]
                                            [(pairq ?a ?b) [?a :val/name ?n] [?b :val/name ?n] [(not= ?a ?b)]]]
                                          (cq/vocab-index) '[?a ?b])))
          rule  (first (filter #(re-find #"^r_pairq" %) lines))]
      (is rule "precondition: the union is emitted as a rule")
      (is (< (.indexOf ^String rule "entity/name") (.indexOf ^String rule "a != b"))
          (str "the predicate must follow the clauses binding it; got: " rule)))))

(deftest a-bound-var-outranks-an-assumed-one-so-a-chain-is-not-ordered-into-a-product
  (testing "head vars are ORDERED against, not bound — a bet on the call site. When a clause joined
            to what this body HAS bound ties with one joined only to a head var, the bound one must
            win: a rule read under a `not` is evaluated whole, the bet pays nothing, and the losing
            order is a cross product. Measured on brian: Region's `declared-dep`, authored
            `within(fr,fa) within(tr,ta) may-depend(fa,ta)`, crossed an 11,044-row relation with
            itself and did not finish in ten minutes; chained through the hop it took 773ms.

            Asserts the LOWERING — the answer is the same either way, which is the problem."
    (let [d     (db)
          lines (first (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(dd ?fr ?tr)]
                                          '[[(within ?r ?anc) [?r :val/up ?anc]]
                                            [(within ?r ?anc) [?r :val/self ?anc]]
                                            [(dd ?fr ?tr)
                                             (within ?fr ?fa) (within ?tr ?ta)
                                             [?e :rel/from ?fa] [?e :rel/to ?ta] [?e :rel/kind :may-depend]]]
                                          (cq/vocab-index) '[?fr ?tr])))
          ^String rule (first (filter #(re-find #"^r_dd\[" %) lines))]
      (is rule "precondition: `dd` is emitted as a rule")
      (is (< (.indexOf rule "r_within[fr, fa]") (.indexOf rule "'rel/from', fa]") (.indexOf rule "r_within[tr, ta]"))
          (str "the hop joining the two ends must run BETWEEN them; got: " rule)))))

(deftest an-unanchored-expansion-opens-on-its-literal
  (testing "an expansion sharing no var with what precedes it scores zero everywhere, and written
            order used to decide — `[?r :rel/from ?a]` first, a scan of every relation in the model,
            where `[?r :rel/kind :k]` is one seek on the `(a, v)` index. Measured on brian: Region's
            interior law, 52.9s → 1.9s. A literal only breaks TIES: a clause that joins something
            already bound still goes first."
    (let [d     (db)
          rules '[[(inl-holds ?a ?b) [?r :rel/from ?a] [?r :rel/to ?b] [?r :rel/kind :inl-holds]]]
          body  (fn [where] (second (binding [cq/*attr-buckets* (cq/buckets-of d)]
                                      (cq/compile-body where rules (cq/vocab-index) '[?n]))))
          ^String free  (body '[[?e :entity/name ?n] (inl-holds ?x ?y)])
          ^String bound (body '[[?e :entity/name ?n] (inl-holds ?e ?y)])]
      (is (< (.indexOf free "'rel/kind', 'inl-holds'") (.indexOf free "'rel/from'"))
          (str "nothing bound: the literal must lead; got: " free))
      (is (< (.indexOf bound "'rel/from', e]") (.indexOf bound "'rel/kind', 'inl-holds'"))
          (str "?e bound: the clause joining it must still lead; got: " bound)))))

(deftest a-negation-binds-nothing-for-the-clauses-ordered-after-it
  (testing "the orderer is positional — a clause taken is a clause whose vars count as bound — and a
            negation binds nothing. Taken early it seeded `bound` with vars nobody had produced, so
            `[?r :rel/to ?t]` was chosen as a probe on a ?t still free: the scan ordering exists to
            avoid. It is held back like a predicate, and a `not-join` is judged on its JOIN vars
            alone. Measured on fukan's own model: the signature-agreement law, 744ms → 149ms
            (and 258ms before the tie-breaks that exposed it — so the fix beat the original too).

            Asserts the LOWERING; `a-negation-inside-an-expansion-still-filters` holds the answer."
    (let [d     (db)
          lines (first (binding [cq/*attr-buckets* (cq/buckets-of d)]
                         (cq/compile-body '[(differs ?a ?b)]
                                          '[[(differs ?a ?b)
                                             [?a :val/kind "f"] [?b :val/kind "f"]
                                             [?r :rel/from ?b] [?r :rel/kind :of] [?r :rel/to ?t]
                                             (not-join [?a ?t] [?s :rel/from ?a] [?s :rel/to ?t])]
                                            [(differs ?a ?b) [?a :val/other ?b]]]
                                          (cq/vocab-index) '[?a ?b])))
          ^String rule (first (filter #(re-find #"^r_differs.*'of'" %) lines))]
      (is rule "precondition: the union is emitted as a rule")
      (is (< (.indexOf rule "'rel/to', t]") (.indexOf rule "not nj_"))
          (str "the negation must follow what binds ?t; got: " rule))
      (is (< (.indexOf rule "'rel/from', b]") (.indexOf rule "'rel/to', t]"))
          (str "…and ?t must be reached from the bound end of the hop, not scanned; got: " rule)))))
