(ns fukan.common.vocab.code.correspondence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            ;; loading infra.model is the composition root — it registers fukan's malli dialect AND its
            ;; Clojure (fact) extractor, so build-model "src" runs the build
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s]
            [fukan.common.typing.malli :as malli]
            [fukan.canvas.core.typing :as typing]
            ;; correspondence is now distributed across the code elements: Operation carries its own
            ;; (the readers below), Module supplies the module↔code-ns bridge + Module structure (loaded
            ;; for its side-effects + the fully-qualified :.../Module tags in the fixtures).
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.operation :as operation]
            [fukan.common.vocab.code.effect :as effect]))

;; register the project dialect's :render for the operation-sig / render-type path used below
;; — per-test, since dialect registration is global mutable state other namespaces touch.
;; (There is no :adheres? bridge — adherence is STRUCTURAL, the :signature comparator over
;; decomposed :in/:out node identities; the demand tests below exercise it end-to-end.)
(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render}) (t)))

(deftest annotated-infra-functions-adhere
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/) with the
            code extracted from src/ on one graph; the three infra functions annotated with
            :malli/schema adhere to their modelled types, so type-drift EXCLUDES them. (We
            assert these three specifically rather than global emptiness, which is fragile as
            more functions get annotated. The false-cases above prove DETECTION fires.)"
    (let [model   (pipeline/build-model "src")
          drifted (law/violation-names model :corresponds/Operation.adheres)]
      (is (not (contains? drifted "load-model"))
          (str "load-model's :malli/schema should adhere to its model; drifted: " drifted))
      (is (not (contains? drifted "get-model"))
          (str "get-model's :malli/schema should adhere to its model; drifted: " drifted))
      (is (not (contains? drifted "refresh-model"))
          (str "refresh-model's :malli/schema should adhere to its model; drifted: " drifted)))))

(deftest multi-arg-order-and-arity-adheres-end-to-end
  (testing "focus-nodes is a real MULTI-ARG function whose :malli/schema matches its modelled
            ordered :in (distinct types, SAME ORDER, SAME ARITY) → NOT type-drifted. The positive end-to-end
            case; mismatch DETECTION (reorder / dropped arg) is covered by adheres-checks-in-order-and-arity."
    (let [model (pipeline/build-model "src")]
      (is (not (contains? (law/violation-names model :corresponds/Operation.adheres) "focus-nodes"))
          "focus-nodes's 2-arg annotation (StructureDb, [vector Clause]) matches its modelled ordered signature"))))

(deftest adheres-demand-gates-a-real-signature-mismatch
  (testing "the GATED :corresponds/Operation.adheres demand (the :signature comparator over twin pairs):
            adherence is STRUCTURAL — a modelled op `f` and its extracted twin adhere iff their :in/:out
            Schema nodes are IDENTICAL (types content-dedup across strata). A twin whose :out is a
            DIFFERENT type node is an offender; the SAME node is green."
    (let [mk (fn [twin-out-eid extra]
               (build/tx-maps->cozo
                (concat
                 [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
                  {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -5 :structure/of :fukan.common.typing.malli/Schema :val/kind "nil"}   ; the MODELLED :out type node
                  {:rel/id "f|out|s5" :rel/from -2 :rel/kind :out :rel/to -5}
                  {:db/id -3 :structure/of :fukan.common.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}
                  {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f" :val/extracted true}
                  {:rel/id "tf|out" :rel/from -4 :rel/kind :out :rel/to twin-out-eid}
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]
                 extra)))
          match    (mk -5 [])                                                              ; twin :out → the SAME node
          mismatch (mk -6 [{:db/id -6 :structure/of :fukan.common.typing.malli/Schema :val/kind "any"}])]  ; twin :out → a DIFFERENT node
      (is (= #{"f"} (law/violation-names mismatch :corresponds/Operation.adheres))
          "a twin whose :out is a different type node is an offender")
      (is (empty? (law/violation-names match :corresponds/Operation.adheres))
          "a twin whose :out is the identical node adheres → green"))))

(deftest adheres-checks-in-order-and-arity
  (testing "structural adherence over :in is SEQUENCE identity — a twin whose :in REORDERS the modelled
            args, or DROPS one, is an offender (the comparator sorts :in by :rel/order and compares eids)."
    (let [;; design f :in = [A B] (types nil, any; shared nodes) → :out C (shared). twin varies only its :in.
          base (fn [twin-in]   ; twin-in: seq of [order type-eid]
                 (build/tx-maps->cozo
                  (concat
                   [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                    {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
                    {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                    {:db/id -10 :structure/of :fukan.common.typing.malli/Schema :val/kind "nil"}      ; type A
                    {:db/id -11 :structure/of :fukan.common.typing.malli/Schema :val/kind "any"}      ; type B
                    {:db/id -12 :structure/of :fukan.common.typing.malli/Schema :val/kind "boolean"}  ; type C (shared :out)
                    {:rel/id "f|in0" :rel/from -2 :rel/kind :in :rel/order 0 :rel/to -10}     ; design :in = [A B]
                    {:rel/id "f|in1" :rel/from -2 :rel/kind :in :rel/order 1 :rel/to -11}
                    {:rel/id "f|out"  :rel/from -2 :rel/kind :out :rel/to -12}
                    {:db/id -3 :structure/of :fukan.common.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}
                    {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f" :val/extracted true}
                    {:rel/id "tf|out" :rel/from -4 :rel/kind :out :rel/to -12}                ; twin :out = C (adheres) → gates the demand in
                    {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]
                   (map-indexed (fn [i [ord eid]]
                                  {:rel/id (str "tf|in" i) :rel/from -4 :rel/kind :in :rel/order ord :rel/to eid})
                                twin-in))))
          match     (base [[0 -10] [1 -11]])   ; twin :in = [A B] — identical
          reordered (base [[0 -11] [1 -10]])   ; twin :in = [B A] — order fires
          short     (base [[0 -10]])]          ; twin :in = [A]   — arity fires
      (is (empty? (law/violation-names match :corresponds/Operation.adheres))
          "an identical :in sequence adheres → green")
      (is (= #{"f"} (law/violation-names reordered :corresponds/Operation.adheres))
          "a reordered :in is an offender (order is checked)")
      (is (= #{"f"} (law/violation-names short :corresponds/Operation.adheres))
          "a dropped :in arg is an offender (arity is checked)"))))

(deftest call-realization-fires-on-an-unrealized-delegation
  (testing "an authored cross-module :delegates with NO actual cross-module :calls is an offender"
    (let [db (build/tx-maps->cozo [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "A" :entity/name "A"}
                   {:db/id -2 :structure/of :fukan.common.vocab.code.module/Module :entity/id "B" :entity/name "B"}
                   {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-a"}
                   {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-b"}
                   {:db/id -5 :structure/of :fukan.common.vocab.code.module/Module :entity/id "X" :entity/name "X" :val/extracted true}
                   {:db/id -6 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "ex" :val/extracted true}
                   {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                   {:rel/id "X|child|ex" :rel/from -5 :rel/kind :child :rel/to -6}
                   {:rel/id "ex|calls|ex2" :rel/from -6 :rel/kind :calls :rel/to -6}])]
      (is (seq (operation/unrealized-delegates db))
          "A->B delegation has no realizing call between corresponding modules → offender"))))

(deftest call-realization-green-on-the-self-model
  (testing "module-level realization is green on the live build-model \"src\""
    (is (empty? (operation/unrealized-delegates (pipeline/build-model "src")))
        "0 unrealized — verified by the design prototype")))

(deftest uncovered-calls-backbone-complete
  (testing "slice 2: every actual cross-module call is now covered by an authored :delegates —
            the backbone is complete (detection of an UNdeclared coupling is proven on a synthetic
            db in fidelity-fires-on-an-undeclared-modelled-coupling)"
    (let [worklist (operation/uncovered-calls (pipeline/build-model "src"))]
      (is (empty? worklist)
          (str "the :delegates backbone is complete; undeclared couplings remain: " worklist)))))

(deftest fidelity-fires-on-an-undeclared-modelled-coupling
  (testing "an actual cross-module call between two MODELLED faculties with no covering :delegates fires"
    (let [db (build/tx-maps->cozo [;; two authored faculty modules a / b (not extracted) → fukan.a / fukan.b are 'modelled'
                   {:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "a" :entity/name "a"}
                   {:db/id -2 :structure/of :fukan.common.vocab.code.module/Module :entity/id "b" :entity/name "b"}
                   {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "oa"}
                   {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "ob"}
                   {:rel/id "a|exposes|oa" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "b|exposes|ob" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   ;; a guard delegate (some intent authored) that does NOT cover a->b
                   {:rel/id "oa|delegates|oa" :rel/from -3 :rel/kind :delegates :rel/to -3}
                   ;; extracted side: fukan.a calls fukan.b, with no covering delegate
                   {:db/id -5 :structure/of :fukan.common.vocab.code.module/Module :entity/id "fukan.a" :entity/name "fukan.a" :val/extracted true}
                   {:db/id -6 :structure/of :fukan.common.vocab.code.module/Module :entity/id "fukan.b" :entity/name "fukan.b" :val/extracted true}
                   {:db/id -7 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "fa" :val/extracted true}
                   {:db/id -8 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "fb" :val/extracted true}
                   {:rel/id "fukan.a|child|fa" :rel/from -5 :rel/kind :child :rel/to -7}
                   {:rel/id "fukan.b|child|fb" :rel/from -6 :rel/kind :child :rel/to -8}
                   {:rel/id "fa|calls|fb" :rel/from -7 :rel/kind :calls :rel/to -8}])]
      (is (= #{"fa"} (operation/unfaithful-calls db))
          "an undeclared coupling between modelled faculties is a fidelity offender")
      (is (= #{["fukan.a" "fukan.b"]} (operation/uncovered-calls db))
          "the same coupling appears in the broader query"))))

(deftest fidelity-green-on-the-self-model
  (testing "every modelled-faculty coupling is declared — the enforced fidelity law is green"
    (is (empty? (operation/unfaithful-calls (pipeline/build-model "src")))
        "0 unfaithful — slice 2 declared every modelled-both-ends coupling")))

(deftest slice-1-self-model-is-clean
  (testing "with :calls grounded, realization + fidelity laws green, and membership scoped, the merged
            design+code self-model has zero law violations"
    (let [model (pipeline/build-model "src")]
      (is (empty? (operation/unrealized-delegates model)) "realization is green")
      (is (empty? (operation/unfaithful-calls model)) "fidelity is green (modelled couplings all declared)")
      (is (empty? (operation/uncovered-calls model)) "coverage worklist is empty — the :delegates backbone is complete")
      (is (empty? (law/check model))
          (str "no law violations on the merged self-model; got: "
               (mapv :law (law/check model)))))))

(deftest encapsulation-fires-on-an-undeclared-public-operation
  (testing "a PUBLIC extracted op with no model twin is an offender; private/export/test-support are exempt"
    (let [db (build/tx-maps->cozo [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "fukan.m" :entity/name "fukan.m" :val/extracted true}
                   {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "leaked"   :val/extracted true}                      ; public, unmodelled → offender
                   {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "hidden"   :val/extracted true :val/private true}      ; exempt: internal
                   {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "exported" :val/extracted true :val/export true}       ; exempt: mechanism
                   {:db/id -5 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "for-test" :val/extracted true :val/test-support true} ; exempt: test-support
                   {:rel/id "m|child|leaked"   :rel/from -1 :rel/kind :child :rel/to -2}
                   {:rel/id "m|child|hidden"   :rel/from -1 :rel/kind :child :rel/to -3}
                   {:rel/id "m|child|exported" :rel/from -1 :rel/kind :child :rel/to -4}
                   {:rel/id "m|child|for-test" :rel/from -1 :rel/kind :child :rel/to -5}])]
      (is (= #{"leaked"} (law/violation-names db :corresponds/Operation.covered))
          "only the public, non-exempt, unmodelled op is flagged by the covered demand"))))

(deftest encapsulation-green-on-the-self-model
  (testing "the self-model's entire public surface is covered by the model or deliberately exempt"
    (is (empty? (law/violation-names (pipeline/build-model "src") :corresponds/Operation.covered))
        "0 unencapsulated — every public function is modelled, private, exported, or test-support")))

;; Tiny model: authored A.op-a :delegates B.op-b. "Same module name" authored/extracted pairs make
;; the :qualified-suffix match trivial ("A" is a suffix of "A" — the exact case).
(defn- delegation-fixture
  "Authored A.op-a :delegates B.op-b; when `wired?`, the extracted MULTI-HOP call path
   op-a -> mid -> op-b (exercising `ext-reaches`' transitive closure over `:calls`)."
  [wired?]
  (build/tx-maps->cozo (cond-> [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "A" :entity/name "A"}
                {:db/id -2 :structure/of :fukan.common.vocab.code.module/Module :entity/id "B" :entity/name "B"}
                {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-a"}
                {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-b"}
                {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                ;; the extracted (code) modules are SEPARATE nodes from the design modules — distinct
                ;; :entity/id so they don't merge — exactly as the real build keeps design ns and code ns
                ;; apart, bridged by `:qualified-suffix` (same name here). The merge would make a single
                ;; node both design+extracted, which never happens in reality.
                {:db/id -5 :structure/of :fukan.common.vocab.code.module/Module :entity/id "Ax" :entity/name "A" :val/extracted true}
                {:db/id -6 :structure/of :fukan.common.vocab.code.module/Module :entity/id "Bx" :entity/name "B" :val/extracted true}
                {:db/id -7  :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-a" :val/extracted true}
                {:db/id -8  :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-b" :val/extracted true}
                {:rel/id "Ax|child|op-a" :rel/from -5 :rel/kind :child :rel/to -7}
                {:rel/id "Bx|child|op-b" :rel/from -6 :rel/kind :child :rel/to -8}]
         wired? (into [{:db/id -11 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "mid" :val/extracted true}
                       {:rel/id "Ax|child|mid" :rel/from -5 :rel/kind :child :rel/to -11}
                       {:rel/id "op-a|calls|mid" :rel/from -7  :rel/kind :calls :rel/to -11}
                       {:rel/id "mid|calls|op-b" :rel/from -11 :rel/kind :calls :rel/to -8}]))))

(deftest generic-twin-matches-the-legacy-op-twin-pairing
  (testing "the kernel twin rules reproduce the retired hand-written op-twin pairs exactly (self-model)"
    (let [db     (pipeline/build-model "src")
          legacy '[[(legacy-twin ?a ?b)
                    [?a :structure/of :fukan.common.vocab.code.operation/Operation] (not [?a :val/extracted true])
                    [?a :entity/name ?n] (in-module ?a ?cm)
                    [?b :structure/of :fukan.common.vocab.code.operation/Operation] [?b :val/extracted true]
                    [?b :entity/name ?n] (in-module ?b ?km)
                    [(name-match :qualified-suffix ?cm ?km)]]]
          rules  (into (s/vocab-rules) legacy)
          pairs  (fn [head] (set (cq/q (into [:find '?a '?b :in '$ '% :where] [(list head '?a '?b)]) db rules)))]
      (is (seq (pairs 'op-twin)) "the self-model has twins")
      (is (= (pairs 'legacy-twin) (pairs 'op-twin))
          "alias(twin)-derived pairs == the legacy hand-written pairing, node for node"))))

(deftest unrealized-dispatch-fires-when-unrealized
  (testing "an authored cross-module delegation with no realizing code path is reported"
    (is (contains? (operation/unrealized-dispatch (delegation-fixture false)) "op-a"))))

(deftest unrealized-dispatch-green-through-calls
  (testing "the delegation is realized once the code reaches the target through its (multi-hop) call graph"
    (is (empty? (operation/unrealized-dispatch (delegation-fixture true)))
        "op-a -> op-b realized transitively via op-a -> mid -> op-b")))

(deftest unrealized-dispatch-green-on-self-model
  (testing "every authored cross-module delegation is realized op-level (transitively) by the live model's call graph"
    (is (empty? (operation/unrealized-dispatch (pipeline/build-model "src")))
        "0 unrealized — every authored :delegates is backed by a real (possibly multi-hop) call path")))

(deftest effect-correspondence-fires-on-an-undeclared-transitive-effect
  (testing "an authored op whose twin TRANSITIVELY reaches an effect it doesn't declare is flagged
            (the dissolved EffectCorrespondence direction — now the generated performs-covered demand);
            declaring the effect on the authored op clears it"
    (let [io     {:db/id -10 :structure/of :fukan.common.vocab.code.effect/Effect :val/name "io"}
          ;; authored f (module m) ; extracted twin f (module fukan.m, corresponds to m) calls g ; g performs :io
          common [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}                          ; authored — declares nothing
                  {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -3 :structure/of :fukan.common.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}   ; code module (corresponds to "m")
                  {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f" :val/extracted true}       ; twin of f
                  {:db/id -5 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "g" :val/extracted true}       ; f calls g
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}
                  {:rel/id "km|child|g" :rel/from -3 :rel/kind :child :rel/to -5}
                  {:rel/id "f|calls|g"  :rel/from -4 :rel/kind :calls :rel/to -5}                          ; f → g (transitive reach)
                  io
                  {:rel/id "g|performs|io" :rel/from -5 :rel/kind :performs :rel/to -10}]                  ; g performs :io
          undeclared-db (build/tx-maps->cozo common)
          declared-db   (build/tx-maps->cozo (conj common {:rel/id "af|performs|io" :rel/from -2 :rel/kind :performs :rel/to -10}))]
      (is (= #{"f"} (effect/undeclared-effects undeclared-db))
          "f's twin transitively reaches :io (via g), but f declares nothing → under-declaration")
      (is (empty? (effect/undeclared-effects declared-db))
          "declaring :io on the authored f satisfies the generated performs-covered demand"))))

(deftest effect-vocab-does-not-name-transitive-reachability
  (testing "effect reachability is expressed by composing :calls* and :performs at the consuming layer"
    (is (not (contains? (ns-publics 'fukan.common.vocab.code.effect) 'reached-effects)))))

(deftest effect-correspondence-green-on-the-self-model
  (testing "the merged self-model declares every effect its code reaches"
    (let [model (pipeline/build-model "src")]
      (is (empty? (effect/undeclared-effects model))
          "0 undeclared effects — design and extraction speak one effect language, to call-graph depth"))))

