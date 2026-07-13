(ns canvas.vocab.code.correspondence-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            ;; loading infra.model is the composition root — it registers fukan's malli dialect AND its
            ;; Clojure (fact) extractor, so build-model "src" runs the build
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s]
            [canvas.vocab.type :as malli]
            [fukan.canvas.core.typing :as typing]
            ;; correspondence is now distributed across the code elements
            [canvas.vocab.code.module :as module]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.effect]
            [canvas.principles.parse-dont-validate :as pdv]
            [canvas.principles.declared-effects :as declared-effects]
            [canvas.principles.layered-architecture :as layered]))

;; register the project dialect (malli render + sigs-adhere?) for the `type-adheres?` path
;; — per-test, since dialect registration is global mutable state other namespaces touch.
(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render :adheres? malli/sigs-adhere?}) (t)))

(deftest sigs-adhere-out-and-in-sequence
  (testing "adherence is OUT-equality AND IN-SEQUENCE-equality (order + arity) on malli function-schemas"
    (is (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                            '[:=> [:cat :Path] :StructureDb])
        "identical schemas adhere")
    (is (not (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                                 '[:=> [:cat :Str] :StructureDb]))
        "an input mismatch breaks adherence")
    (is (not (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                                 '[:=> [:cat :Path] :Other]))
        "an output mismatch breaks adherence")
    (testing "inputs compared as a SEQUENCE — order IS checked"
      (is (not (malli/sigs-adhere? '[:=> [:cat :A :B] :R]
                                   '[:=> [:cat :B :A] :R]))
          "reordered inputs do NOT adhere (order matters)"))
    (testing "inputs compared as a SEQUENCE — arity IS checked"
      (is (not (malli/sigs-adhere? '[:=> [:cat :A :B] :R]
                                   '[:=> [:cat :A] :R]))
          "a dropped argument does NOT adhere (arity matters)"))))

(deftest type-adheres-dispatches-through-the-dialect
  (testing "type-adheres? routes both forms through the registered :adheres? bridge"
    (is (true?  (typing/type-adheres? '[:=> [:cat :Path] :StructureDb]
                                      '[:=> [:cat :Path] :StructureDb])))
    (is (false? (typing/type-adheres? '[:=> [:cat :Path] :StructureDb]
                                      '[:=> [:cat :Str] :StructureDb])))))

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
  (testing "materialize-over is a real MULTI-ARG function whose :malli/schema matches its
            modelled ordered :in — same types, SAME ORDER, SAME ARITY — so it is NOT type-drifted,
            and the comparison fires on a reordered / dropped-arg code signature."
    (let [model (pipeline/build-model "src")
          op    (ffirst (cq/q '[:find ?e
                               :where [?e :structure/of :canvas.vocab.code.operation/Operation] (not [?e :val/extracted true])
                                      [?e :entity/name "materialize-over"]]
                             model))
          sig   (operation/operation-sig model op)]
      ;; integration: multi-arg, in order → adheres → absent from type-drift
      (is (not (contains? (law/violation-names model :corresponds/Operation.adheres) "materialize-over"))
          "materialize-over's annotation matches its modelled ordered signature")
      ;; the model renders :in positionally, in order
      (is (= [:=> [:cat :StructureDb :ProjectionName [:vector :Eid]] :Instruction] sig)
          "modelled :in renders in :rel/order order")
      ;; detection: a REORDERED code-sig does NOT adhere (order fires)
      (is (false? (typing/type-adheres? sig '[:=> [:cat :ProjectionName :StructureDb [:vector :Eid]] :Instruction]))
          "reordered inputs do not adhere")
      ;; detection: a DROPPED-arg code-sig does NOT adhere (arity fires)
      (is (false? (typing/type-adheres? sig '[:=> [:cat :StructureDb :ProjectionName] :Instruction]))
          "dropped argument does not adhere"))))

(deftest adheres-demand-gates-a-real-signature-mismatch
  (testing "the GATED :corresponds/Operation.adheres demand (the :signature comparator over twin
            pairs): a modelled op `f` (no :in/:out → renders [:=> [:cat] :nil]) whose extracted twin
            declares a DIFFERENT :val/sig is an offender; a twin whose sig exactly adheres is green."
    (let [mk (fn [sig]
               (build/tx-maps->cozo
                [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/name "m"}
                 {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "f"}
                 {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                 {:db/id -3 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}
                 {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "f"
                  :val/extracted true :val/sig sig}
                 {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]))
          mismatch (mk "[:=> [:cat] :any]")   ; design renders [:=> [:cat] :nil] → out mismatch
          match    (mk "[:=> [:cat] :nil]")]
      (is (= #{"f"} (law/violation-names mismatch :corresponds/Operation.adheres))
          "a twin whose realized signature differs from the modelled one is an offender")
      (is (empty? (law/violation-names match :corresponds/Operation.adheres))
          "a twin whose realized signature exactly adheres is green"))))

(deftest call-realization-fires-on-an-unrealized-delegation
  (testing "an authored cross-module :delegates with NO actual cross-module :calls is an offender"
    (let [db (build/tx-maps->cozo [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/id "A" :entity/name "A"}
                   {:db/id -2 :structure/of :canvas.vocab.code.module/Module :entity/id "B" :entity/name "B"}
                   {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-a"}
                   {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-b"}
                   {:db/id -5 :structure/of :canvas.vocab.code.module/Module :entity/id "X" :entity/name "X" :val/extracted true}
                   {:db/id -6 :structure/of :canvas.vocab.code.operation/Operation :entity/name "ex" :val/extracted true}
                   {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                   {:rel/id "X|child|ex" :rel/from -5 :rel/kind :child :rel/to -6}
                   {:rel/id "ex|calls|ex2" :rel/from -6 :rel/kind :calls :rel/to -6}])]
      (is (seq (layered/unrealized-delegates db))
          "A->B delegation has no realizing call between corresponding modules → offender"))))

(deftest call-realization-green-on-the-self-model
  (testing "module-level realization is green on the live build-model \"src\""
    (is (empty? (layered/unrealized-delegates (pipeline/build-model "src")))
        "0 unrealized — verified by the design prototype")))

(deftest uncovered-calls-backbone-complete
  (testing "slice 2: every actual cross-module call is now covered by an authored :delegates —
            the backbone is complete (detection of an UNdeclared coupling is proven on a synthetic
            db in fidelity-fires-on-an-undeclared-modelled-coupling)"
    (let [worklist (layered/uncovered-calls (pipeline/build-model "src"))]
      (is (empty? worklist)
          (str "the :delegates backbone is complete; undeclared couplings remain: " worklist)))))

(deftest fidelity-fires-on-an-undeclared-modelled-coupling
  (testing "an actual cross-module call between two MODELLED faculties with no covering :delegates fires"
    (let [db (build/tx-maps->cozo [;; two authored faculty modules a / b (not extracted) → fukan.a / fukan.b are 'modelled'
                   {:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/id "a" :entity/name "a"}
                   {:db/id -2 :structure/of :canvas.vocab.code.module/Module :entity/id "b" :entity/name "b"}
                   {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "oa"}
                   {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "ob"}
                   {:rel/id "a|exposes|oa" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "b|exposes|ob" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   ;; a guard delegate (some intent authored) that does NOT cover a->b
                   {:rel/id "oa|delegates|oa" :rel/from -3 :rel/kind :delegates :rel/to -3}
                   ;; extracted side: fukan.a calls fukan.b, with no covering delegate
                   {:db/id -5 :structure/of :canvas.vocab.code.module/Module :entity/id "fukan.a" :entity/name "fukan.a" :val/extracted true}
                   {:db/id -6 :structure/of :canvas.vocab.code.module/Module :entity/id "fukan.b" :entity/name "fukan.b" :val/extracted true}
                   {:db/id -7 :structure/of :canvas.vocab.code.operation/Operation :entity/name "fa" :val/extracted true}
                   {:db/id -8 :structure/of :canvas.vocab.code.operation/Operation :entity/name "fb" :val/extracted true}
                   {:rel/id "fukan.a|child|fa" :rel/from -5 :rel/kind :child :rel/to -7}
                   {:rel/id "fukan.b|child|fb" :rel/from -6 :rel/kind :child :rel/to -8}
                   {:rel/id "fa|calls|fb" :rel/from -7 :rel/kind :calls :rel/to -8}])]
      (is (= #{"fa"} (layered/unfaithful-calls db))
          "an undeclared coupling between modelled faculties is a fidelity offender")
      (is (= #{["fukan.a" "fukan.b"]} (layered/uncovered-calls db))
          "the same coupling appears in the broader query"))))

(deftest fidelity-green-on-the-self-model
  (testing "every modelled-faculty coupling is declared — the enforced fidelity law is green"
    (is (empty? (layered/unfaithful-calls (pipeline/build-model "src")))
        "0 unfaithful — slice 2 declared every modelled-both-ends coupling")))

(deftest slice-1-self-model-is-clean
  (testing "with :calls grounded, realization + fidelity laws green, and membership scoped, the merged
            design+code self-model has zero law violations"
    (let [model (pipeline/build-model "src")]
      (is (empty? (layered/unrealized-delegates model)) "realization is green")
      (is (empty? (layered/unfaithful-calls model)) "fidelity is green (modelled couplings all declared)")
      (is (empty? (layered/uncovered-calls model)) "coverage worklist is empty — the :delegates backbone is complete")
      (is (empty? (law/check model))
          (str "no law violations on the merged self-model; got: "
               (mapv :law (law/check model)))))))

(deftest encapsulation-fires-on-an-undeclared-public-operation
  (testing "a PUBLIC extracted op with no model twin is an offender; private/export/test-support are exempt"
    (let [db (build/tx-maps->cozo [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/id "fukan.m" :entity/name "fukan.m" :val/extracted true}
                   {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "leaked"   :val/extracted true}                      ; public, unmodelled → offender
                   {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "hidden"   :val/extracted true :val/private true}      ; exempt: internal
                   {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "exported" :val/extracted true :val/export true}       ; exempt: mechanism
                   {:db/id -5 :structure/of :canvas.vocab.code.operation/Operation :entity/name "for-test" :val/extracted true :val/test-support true} ; exempt: test-support
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

(deftest defmultis-are-extracted-and-modelled
  (testing "both defmultis are extracted as Operations AND covered by the model (not undeclared public surface)"
    (let [m         (pipeline/build-model "src")
          extracted (set (cq/q '[:find [?n ...]
                                :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?n]] m))
          worklist  (law/violation-names m :corresponds/Operation.covered)]
      (is (contains? extracted "render-base")    "render-base (defmulti) is extracted as an Operation")
      (is (contains? extracted "render-finding") "render-finding (defmulti) is extracted as an Operation")
      (is (not (contains? worklist "render-base"))    "render-base is covered, not an undeclared public surface")
      (is (not (contains? worklist "render-finding")) "render-finding is covered, not an undeclared public surface"))))

;; Tiny model: authored A.op-a :delegates B.op-b. "Same module name" authored/extracted pairs make
;; module-corresponds? trivial (segs "A" is a suffix of segs "A").
(defn- delegation-fixture
  "Authored A.op-a :delegates B.op-b; when `wired?`, the extracted MULTI-HOP call path
   op-a -> mid -> op-b (exercising `ext-reaches`' transitive closure over `:calls`)."
  [wired?]
  (build/tx-maps->cozo (cond-> [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/id "A" :entity/name "A"}
                {:db/id -2 :structure/of :canvas.vocab.code.module/Module :entity/id "B" :entity/name "B"}
                {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-a"}
                {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-b"}
                {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                ;; the extracted (code) modules are SEPARATE nodes from the design modules — distinct
                ;; :entity/id so they don't merge — exactly as the real build keeps design ns and code ns
                ;; apart, bridged by `module-corresponds?` (same name here). The merge would make a single
                ;; node both design+extracted, which never happens in reality.
                {:db/id -5 :structure/of :canvas.vocab.code.module/Module :entity/id "Ax" :entity/name "A" :val/extracted true}
                {:db/id -6 :structure/of :canvas.vocab.code.module/Module :entity/id "Bx" :entity/name "B" :val/extracted true}
                {:db/id -7  :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-a" :val/extracted true}
                {:db/id -8  :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-b" :val/extracted true}
                {:rel/id "Ax|child|op-a" :rel/from -5 :rel/kind :child :rel/to -7}
                {:rel/id "Bx|child|op-b" :rel/from -6 :rel/kind :child :rel/to -8}]
         wired? (into [{:db/id -11 :structure/of :canvas.vocab.code.operation/Operation :entity/name "mid" :val/extracted true}
                       {:rel/id "Ax|child|mid" :rel/from -5 :rel/kind :child :rel/to -11}
                       {:rel/id "op-a|calls|mid" :rel/from -7  :rel/kind :calls :rel/to -11}
                       {:rel/id "mid|calls|op-b" :rel/from -11 :rel/kind :calls :rel/to -8}]))))

(deftest generic-twin-matches-the-legacy-op-twin-pairing
  (testing "the kernel twin rules reproduce the retired hand-written op-twin pairs exactly (self-model)"
    (let [db     (pipeline/build-model "src")
          legacy '[[(legacy-twin ?a ?b)
                    [?a :structure/of :canvas.vocab.code.operation/Operation] (not [?a :val/extracted true])
                    [?a :entity/name ?n] (in-module ?a ?cm)
                    [?b :structure/of :canvas.vocab.code.operation/Operation] [?b :val/extracted true]
                    [?b :entity/name ?n] (in-module ?b ?km)
                    [(canvas.vocab.code.module/module-corresponds? ?cm ?km)]]]
          rules  (into (s/vocab-rules) legacy)
          pairs  (fn [head] (set (cq/q (into [:find '?a '?b :in '$ '% :where] [(list head '?a '?b)]) db rules)))]
      (is (seq (pairs 'op-twin)) "the self-model has twins")
      (is (= (pairs 'legacy-twin) (pairs 'op-twin))
          "alias(twin)-derived pairs == the legacy hand-written pairing, node for node"))))

(deftest unrealized-dispatch-fires-when-unrealized
  (testing "an authored cross-module delegation with no realizing code path is reported"
    (is (contains? (module/unrealized-dispatch (delegation-fixture false)) "op-a"))))

(deftest unrealized-dispatch-green-through-calls
  (testing "the delegation is realized once the code reaches the target through its (multi-hop) call graph"
    (is (empty? (module/unrealized-dispatch (delegation-fixture true)))
        "op-a -> op-b realized transitively via op-a -> mid -> op-b")))

(deftest unrealized-dispatch-green-on-self-model
  (testing "every authored cross-module delegation is realized op-level (transitively) by the live model's call graph"
    (is (empty? (module/unrealized-dispatch (pipeline/build-model "src")))
        "0 unrealized — every authored :delegates is backed by a real (possibly multi-hop) call path")))

(deftest effect-correspondence-fires-on-an-undeclared-transitive-effect
  (testing "an authored op whose twin TRANSITIVELY reaches an effect it doesn't declare is flagged
            (the dissolved EffectCorrespondence direction — now the generated performs-covered demand);
            declaring the effect on the authored op clears it"
    (let [io     {:db/id -10 :structure/of :canvas.vocab.code.effect/Effect :val/name "io"}
          ;; authored f (module m) ; extracted twin f (module fukan.m, corresponds to m) calls g ; g performs :io
          common [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "f"}                          ; authored — declares nothing
                  {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -3 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}   ; code module (corresponds to "m")
                  {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "f" :val/extracted true}       ; twin of f
                  {:db/id -5 :structure/of :canvas.vocab.code.operation/Operation :entity/name "g" :val/extracted true}       ; f calls g
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}
                  {:rel/id "km|child|g" :rel/from -3 :rel/kind :child :rel/to -5}
                  {:rel/id "f|calls|g"  :rel/from -4 :rel/kind :calls :rel/to -5}                          ; f → g (transitive reach)
                  io
                  {:rel/id "g|performs|io" :rel/from -5 :rel/kind :performs :rel/to -10}]                  ; g performs :io
          undeclared-db (build/tx-maps->cozo common)
          declared-db   (build/tx-maps->cozo (conj common {:rel/id "af|performs|io" :rel/from -2 :rel/kind :performs :rel/to -10}))]
      (is (= #{"f"} (declared-effects/undeclared-effects undeclared-db))
          "f's twin transitively reaches :io (via g), but f declares nothing → under-declaration")
      (is (empty? (declared-effects/undeclared-effects declared-db))
          "declaring :io on the authored f satisfies the generated performs-covered demand"))))

(deftest effect-vocab-does-not-name-transitive-reachability
  (testing "effect reachability is expressed by composing :calls* and :performs at the consuming layer"
    (is (not (contains? (ns-publics 'canvas.vocab.code.effect) 'reached-effects)))))

(deftest effect-and-totality-green-on-the-self-model
  (testing "the merged self-model declares every effect its code reaches, and its trusted core is total"
    (let [model (pipeline/build-model "src")]
      (is (empty? (declared-effects/undeclared-effects model))
          "0 undeclared effects — design and extraction speak one effect language, to call-graph depth")
      (is (empty? (pdv/totality-violations model))
          "0 totality violations — every trusted-core reader (its :in is a declared TrustBoundary) is total"))))

(deftest totality-fires-on-a-partial-trusted-reader
  (testing "an authored reader whose :in references a declared TrustBoundary Kind, and whose extracted
            twin performs :throws, is flagged; with NO TrustBoundary declared the law is vacuous —
            proving the trust boundary is read from config, not the hardcoded StructureDb"
    (let [throws {:db/id -10 :structure/of :canvas.vocab.code.effect/Effect :val/name "throws"}
          k      {:db/id -20 :structure/of :canvas.vocab.code.kind/Kind :entity/name "TrustDb"}
          tb     [{:db/id -21 :structure/of :canvas.principles.parse-dont-validate/TrustBoundary}
                  {:rel/id "tb|kind|k" :rel/from -21 :rel/kind :kind :rel/to -20}]
          ;; authored reader (module m), :in references TrustDb ; extracted twin (fukan.m) throws
          common [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "reader"}        ; authored
                  {:rel/id "m|exposes|reader" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -22 :structure/of :canvas.vocab.type/Schema :val/kind "ref" :val/ref "TrustDb"}        ; the :in ref schema, names TrustDb
                  {:rel/id "reader|in|sch" :rel/from -2 :rel/kind :in :rel/to -22}                              ; reader :in → the ref
                  {:db/id -3 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true} ; corresponds to "m"
                  {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "reader" :val/extracted true} ; twin
                  {:rel/id "km|child|reader" :rel/from -3 :rel/kind :child :rel/to -4}
                  {:rel/id "twin|performs|throws" :rel/from -4 :rel/kind :performs :rel/to -10}                 ; twin throws
                  throws k]
          with-tb    (build/tx-maps->cozo (concat common tb))
          without-tb (build/tx-maps->cozo common)]
      (is (= #{"reader"} (pdv/totality-violations with-tb))
          "the trusted reader's twin throws → a totality violation")
      (is (empty? (pdv/totality-violations without-tb))
          "no TrustBoundary declared → vacuous; the law reads the designation, not a hardcoded name"))))

(deftest partiality-spread-lives-with-parse-dont-validate
  (testing "partiality spread is a principle reading, not generic Effect vocabulary"
    (let [db (build/tx-maps->cozo
              [{:db/id -10 :structure/of :canvas.vocab.code.effect/Effect :val/name "throws"}
               {:db/id -1 :structure/of :canvas.vocab.code.operation/Operation :entity/name "caller" :val/extracted true}
               {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "thrower" :val/extracted true}
               {:rel/id "caller|calls|thrower" :rel/from -1 :rel/kind :calls :rel/to -2}
               {:rel/id "thrower|performs|throws" :rel/from -2 :rel/kind :performs :rel/to -10}])]
      (is (= {:direct #{"thrower"} :transitive-only #{"caller"}}
             (pdv/throw-spread db))))))

;; The Lens-act Coverage law (probe-reader → Lens) was DISSOLVED on 2026-06-29: readings became
;; Projections with a mandatory :through Lens slot, so the guarantee is now structural, not a law.

;; ── produces: the derived op→Kind output relation (the :out mirror of Totality's :in navigation) ──

(deftest produces-derives-authored-output-kinds
  (testing "(produces ?o ?k) pairs an authored op with the Kind its :out ref names; boolean outs derive nothing"
    (let [k      {:db/id -20 :structure/of :canvas.vocab.code.kind/Kind :entity/name "Artifact"}
          parser [{:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "parse-it"}
                  {:db/id -22 :structure/of :canvas.vocab.type/Schema :val/kind "ref" :val/ref "Artifact"}
                  {:rel/id "p|out|sch" :rel/from -2 :rel/kind :out :rel/to -22}]
          check* [{:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "check-it"}
                  {:db/id -23 :structure/of :canvas.vocab.type/Schema :val/kind "boolean"}
                  {:rel/id "c|out|bool" :rel/from -3 :rel/kind :out :rel/to -23}]
          ;; an EXTRACTED op with the same :out shape must NOT derive (produces is authored-side)
          extr   [{:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "parse-it" :val/extracted true}
                  {:rel/id "e|out|sch" :rel/from -4 :rel/kind :out :rel/to -22}]
          db     (build/tx-maps->cozo (concat [k] parser check* extr))
          pairs  (set (cq/q '[:find ?on ?kn :in $ %
                              :where (produces ?o ?k) [?o :entity/name ?on] [?k :entity/name ?kn]]
                            db (s/vocab-rules)))]
      (is (= #{["parse-it" "Artifact"]} pairs)
          "only the authored ref-out op derives; boolean-out and extracted ops do not"))))

;; ── TrustBoundary :parsed-by — declaration cross-checked against structure ──────────────────────

(defn- law-violations
  "check results whose :law description contains `substr`."
  [db substr]
  (filter #(clojure.string/includes? (:law %) substr) (law/check db)))

(deftest parser-declaration-cross-checked-against-produces
  (testing "a declared parser that does not produce the boundary kind is an offender pair"
    (let [k       {:db/id -20 :structure/of :canvas.vocab.code.kind/Kind :entity/name "Artifact"}
          ;; honest parser: :out ref names Artifact
          parser  [{:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "parse-it"}
                   {:db/id -22 :structure/of :canvas.vocab.type/Schema :val/kind "ref" :val/ref "Artifact"}
                   {:rel/id "p|out|sch" :rel/from -2 :rel/kind :out :rel/to -22}]
          ;; imposter: boolean :out — declared as parser but produces nothing
          imposter [{:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "check-it"}
                    {:db/id -23 :structure/of :canvas.vocab.type/Schema :val/kind "boolean"}
                    {:rel/id "c|out|bool" :rel/from -3 :rel/kind :out :rel/to -23}]
          tb      (fn [op-id suffix]
                    [{:db/id -21 :structure/of :canvas.principles.parse-dont-validate/TrustBoundary}
                     {:rel/id (str "tb|kind|k" suffix) :rel/from -21 :rel/kind :kind :rel/to -20}
                     {:rel/id (str "tb|parsed-by|" suffix) :rel/from -21 :rel/kind :parsed-by :rel/to op-id}])
          honest   (build/tx-maps->cozo (concat [k] parser imposter (tb -2 "honest")))
          lying    (build/tx-maps->cozo (concat [k] parser imposter (tb -3 "lying")))]
      (is (empty? (law-violations honest "every declared parser produces"))
          "a parser whose :out names the boundary kind satisfies the cross-check")
      (is (= 1 (count (mapcat :offenders (law-violations lying "every declared parser produces"))))
          "declaring the boolean-out op as parser fires the cross-check"))))

(deftest trust-boundary-requires-a-parser
  (testing "the [:+ :parsed-by] cardinality makes an undeclared parser a structural violation"
    (let [k  {:db/id -20 :structure/of :canvas.vocab.code.kind/Kind :entity/name "Artifact"}
          tb [{:db/id -21 :structure/of :canvas.principles.parse-dont-validate/TrustBoundary}
              {:rel/id "tb|kind|k" :rel/from -21 :rel/kind :kind :rel/to -20}]
          db (build/tx-maps->cozo (concat [k] tb))]
      (is (seq (law-violations db "parsed-by"))
          "a TrustBoundary declaring no parser violates the generated cardinality law"))))
