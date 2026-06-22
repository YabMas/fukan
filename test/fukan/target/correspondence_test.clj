(ns fukan.target.correspondence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            ;; loading infra.model is the composition root — it registers fukan's
            ;; malli dialect AND its Clojure extractor, and offers build/load of the model
            [fukan.infra.model :as infra-model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.substrate :as sub]
            [fukan.dialect.malli :as malli]
            [fukan.canvas.core.typing :as typing]
            [fukan.target.correspondence :as corr]))

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
    (let [model   (infra-model/load-model "src")
          drifted (corr/type-drifted-operations model)]
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
    (let [model (infra-model/load-model "src")
          op    (ffirst (d/q '[:find ?e
                               :where [?e :structure/of :lib.code/Operation] (not [?e :val/extracted true])
                                      [?e :entity/name "materialize-over"]]
                             model))
          sig   (corr/operation-sig model op)]
      ;; integration: multi-arg, in order → adheres → absent from type-drift
      (is (not (contains? (corr/type-drifted-operations model) "materialize-over"))
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

(deftest call-realization-fires-on-an-unrealized-delegation
  (testing "an authored cross-module :delegates with NO actual cross-module :calls is an offender"
    (let [db (-> (sub/create)
                 (d/db-with
                  [{:db/id -1 :structure/of :lib.code/Module :entity/id "A" :entity/name "A"}
                   {:db/id -2 :structure/of :lib.code/Module :entity/id "B" :entity/name "B"}
                   {:db/id -3 :structure/of :lib.code/Operation :entity/name "op-a"}
                   {:db/id -4 :structure/of :lib.code/Operation :entity/name "op-b"}
                   {:db/id -5 :structure/of :lib.code/Module :entity/id "X" :entity/name "X" :val/extracted true}
                   {:db/id -6 :structure/of :lib.code/Operation :entity/name "ex" :val/extracted true}
                   {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                   {:rel/id "X|child|ex" :rel/from -5 :rel/kind :child :rel/to -6}
                   {:rel/id "ex|calls|ex2" :rel/from -6 :rel/kind :calls :rel/to -6}]))]
      (is (seq (corr/unrealized-delegates db))
          "A->B delegation has no realizing call between corresponding modules → offender"))))

(deftest call-realization-green-on-the-self-model
  (testing "module-level realization is green on the live build-model \"src\""
    (is (empty? (corr/unrealized-delegates (pipeline/build-model "src")))
        "0 unrealized — verified by the design prototype")))

(deftest uncovered-calls-backbone-complete
  (testing "slice 2: every actual cross-module call is now covered by an authored :delegates —
            the backbone is complete (detection of an UNdeclared coupling is proven on a synthetic
            db in fidelity-fires-on-an-undeclared-modelled-coupling)"
    (let [worklist (corr/uncovered-calls (pipeline/build-model "src"))]
      (is (empty? worklist)
          (str "the :delegates backbone is complete; undeclared couplings remain: " worklist)))))

(deftest fidelity-fires-on-an-undeclared-modelled-coupling
  (testing "an actual cross-module call between two MODELLED faculties with no covering :delegates fires"
    (let [db (-> (sub/create)
                 (d/db-with
                  [;; two authored faculty modules a / b (not extracted) → fukan.a / fukan.b are 'modelled'
                   {:db/id -1 :structure/of :lib.code/Module :entity/id "a" :entity/name "a"}
                   {:db/id -2 :structure/of :lib.code/Module :entity/id "b" :entity/name "b"}
                   {:db/id -3 :structure/of :lib.code/Operation :entity/name "oa"}
                   {:db/id -4 :structure/of :lib.code/Operation :entity/name "ob"}
                   {:rel/id "a|exposes|oa" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "b|exposes|ob" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   ;; a guard delegate (some intent authored) that does NOT cover a->b
                   {:rel/id "oa|delegates|oa" :rel/from -3 :rel/kind :delegates :rel/to -3}
                   ;; extracted side: fukan.a calls fukan.b, with no covering delegate
                   {:db/id -5 :structure/of :lib.code/Module :entity/id "fukan.a" :entity/name "fukan.a" :val/extracted true}
                   {:db/id -6 :structure/of :lib.code/Module :entity/id "fukan.b" :entity/name "fukan.b" :val/extracted true}
                   {:db/id -7 :structure/of :lib.code/Operation :entity/name "fa" :val/extracted true}
                   {:db/id -8 :structure/of :lib.code/Operation :entity/name "fb" :val/extracted true}
                   {:rel/id "fukan.a|child|fa" :rel/from -5 :rel/kind :child :rel/to -7}
                   {:rel/id "fukan.b|child|fb" :rel/from -6 :rel/kind :child :rel/to -8}
                   {:rel/id "fa|calls|fb" :rel/from -7 :rel/kind :calls :rel/to -8}]))]
      (is (= #{"fa"} (corr/unfaithful-calls db))
          "an undeclared coupling between modelled faculties is a fidelity offender")
      (is (= #{["fukan.a" "fukan.b"]} (corr/uncovered-calls db))
          "the same coupling appears in the broader query"))))

(deftest fidelity-green-on-the-self-model
  (testing "every modelled-faculty coupling is declared — the enforced fidelity law is green"
    (is (empty? (corr/unfaithful-calls (pipeline/build-model "src")))
        "0 unfaithful — slice 2 declared every modelled-both-ends coupling")))

(deftest slice-1-self-model-is-clean
  (testing "with :calls grounded, realization + fidelity laws green, and membership scoped, the merged
            design+code self-model has zero law violations"
    (let [model (pipeline/build-model "src")]
      (is (empty? (corr/unrealized-delegates model)) "realization is green")
      (is (empty? (corr/unfaithful-calls model)) "fidelity is green (modelled couplings all declared)")
      (is (empty? (corr/uncovered-calls model)) "coverage worklist is empty — the :delegates backbone is complete")
      (is (empty? (s/check model))
          (str "no law violations on the merged self-model; got: "
               (mapv :law (s/check model)))))))

(deftest encapsulation-fires-on-an-undeclared-public-operation
  (testing "a PUBLIC extracted op with no model twin is an offender; private/export/test-support are exempt"
    (let [db (-> (sub/create)
                 (d/db-with
                  [{:db/id -1 :structure/of :lib.code/Module :entity/id "fukan.m" :entity/name "fukan.m" :val/extracted true}
                   {:db/id -2 :structure/of :lib.code/Operation :entity/name "leaked"   :val/extracted true}                      ; public, unmodelled → offender
                   {:db/id -3 :structure/of :lib.code/Operation :entity/name "hidden"   :val/extracted true :val/private true}      ; exempt: internal
                   {:db/id -4 :structure/of :lib.code/Operation :entity/name "exported" :val/extracted true :val/export true}       ; exempt: mechanism
                   {:db/id -5 :structure/of :lib.code/Operation :entity/name "for-test" :val/extracted true :val/test-support true} ; exempt: test-support
                   {:rel/id "m|child|leaked"   :rel/from -1 :rel/kind :child :rel/to -2}
                   {:rel/id "m|child|hidden"   :rel/from -1 :rel/kind :child :rel/to -3}
                   {:rel/id "m|child|exported" :rel/from -1 :rel/kind :child :rel/to -4}
                   {:rel/id "m|child|for-test" :rel/from -1 :rel/kind :child :rel/to -5}]))]
      (is (= #{"leaked"} (corr/uncovered-public-operations db))
          "only the public, non-exempt, unmodelled op is flagged by the Encapsulation law"))))

(deftest encapsulation-green-on-the-self-model
  (testing "the self-model's entire public surface is covered by the model or deliberately exempt"
    (is (empty? (corr/uncovered-public-operations (pipeline/build-model "src")))
        "0 unencapsulated — every public function is modelled, private, exported, or test-support")))

(deftest defmultis-are-extracted-and-modelled
  (testing "both defmultis are extracted as Operations AND covered by the model (not undeclared public surface)"
    (let [m         (pipeline/build-model "src")
          extracted (set (d/q '[:find [?n ...]
                                :where [?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?n]] m))
          worklist  (corr/uncovered-public-operations m)]
      (is (contains? extracted "run-probe")   "run-probe (defmulti) is extracted as an Operation")
      (is (contains? extracted "render-base") "render-base (defmulti) is extracted as an Operation")
      (is (not (contains? worklist "run-probe"))   "run-probe is covered, not an undeclared public surface")
      (is (not (contains? worklist "render-base")) "render-base is covered, not an undeclared public surface"))))

(deftest run-probe-dispatches-to-the-eight-leaves
  (testing "run-probe is modelled as a dispatch point fanning out to all eight probe handlers"
    (let [m  (pipeline/build-model "src")
          ;; the AUTHORED run-probe (not the extracted twin) carries the fan-out
          dp (ffirst (d/q '[:find ?o :where [?o :structure/of :lib.code/Operation] [?o :entity/name "run-probe"]
                                          (not [?o :val/extracted true])] m))]
      (is (= #{"probe-survey" "probe-patterns" "probe-consistency" "probe-tar-pit"
               "probe-integrity" "probe-coverage" "probe-drift" "probe-type-drift"}
             (set (d/q '[:find [?hn ...] :in $ ?dp
                         :where [?r :rel/from ?dp] [?r :rel/kind :dispatches-to] [?r :rel/to ?h] [?h :entity/name ?hn]]
                       m dp)))
          "the modelled fan-out names every probe leaf"))))

(deftest run-extractor-dispatches-to-the-registered-extractor
  (testing "the extraction plug-point is modelled as a dispatch point routing to the registered extractor"
    (let [m  (pipeline/build-model "src")
          dp (ffirst (d/q '[:find ?o :where [?o :structure/of :lib.code/Operation] [?o :entity/name "run-extractor"]
                                          (not [?o :val/extracted true])] m))]
      (is (= #{"extract"}
             (set (d/q '[:find [?hn ...] :in $ ?dp
                         :where [?r :rel/from ?dp] [?r :rel/kind :dispatches-to] [?r :rel/to ?h] [?h :entity/name ?hn]]
                       m dp)))
          "run-extractor dispatches to the registered Clojure extractor (extract)"))))

;; Tiny model: authored A.op-a :delegates B.op-b. "Same module name" authored/extracted pairs make
;; module-corresponds? trivial (segs "A" is a suffix of segs "A").
(defn- dispatch-fixture
  "Authored A.op-a :delegates B.op-b; a dispatch point dp in A with :dispatches-to handler h; and,
   when `wired?`, the extracted call path op-a -> dp ... h -> op-b."
  [wired?]
  (-> (sub/create)
      (d/db-with
       (cond-> [{:db/id -1 :structure/of :lib.code/Module :entity/id "A" :entity/name "A"}
                {:db/id -2 :structure/of :lib.code/Module :entity/id "B" :entity/name "B"}
                {:db/id -3 :structure/of :lib.code/Operation :entity/name "op-a"}
                {:db/id -4 :structure/of :lib.code/Operation :entity/name "op-b"}
                {:db/id -9  :structure/of :lib.code/Operation :entity/name "dp"}
                {:db/id -10 :structure/of :lib.code/Operation :entity/name "h"}
                {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                {:rel/id "A|child|dp"     :rel/from -1 :rel/kind :child   :rel/to -9}
                {:rel/id "A|child|h"      :rel/from -1 :rel/kind :child   :rel/to -10}
                {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                {:rel/id "dp|dispatches-to|h"  :rel/from -9 :rel/kind :dispatches-to :rel/to -10}
                {:db/id -5 :structure/of :lib.code/Module :entity/id "A" :entity/name "A" :val/extracted true}
                {:db/id -6 :structure/of :lib.code/Module :entity/id "B" :entity/name "B" :val/extracted true}
                {:db/id -7  :structure/of :lib.code/Operation :entity/name "op-a" :val/extracted true}
                {:db/id -8  :structure/of :lib.code/Operation :entity/name "op-b" :val/extracted true}
                {:db/id -11 :structure/of :lib.code/Operation :entity/name "dp"   :val/extracted true}
                {:db/id -12 :structure/of :lib.code/Operation :entity/name "h"    :val/extracted true}
                {:rel/id "Ax|child|op-a" :rel/from -5 :rel/kind :child :rel/to -7}
                {:rel/id "Ax|child|dp"   :rel/from -5 :rel/kind :child :rel/to -11}
                {:rel/id "Ax|child|h"    :rel/from -5 :rel/kind :child :rel/to -12}
                {:rel/id "Bx|child|op-b" :rel/from -6 :rel/kind :child :rel/to -8}]
         wired? (into [{:rel/id "op-a|calls|dp" :rel/from -7  :rel/kind :calls :rel/to -11}
                       {:rel/id "h|calls|op-b"  :rel/from -12 :rel/kind :calls :rel/to -8}])))))

(deftest unrealized-dispatch-fires-when-unrealized
  (testing "an authored cross-module delegation with no realizing code path is reported"
    (is (contains? (corr/unrealized-dispatch (dispatch-fixture false)) "op-a"))))

(deftest unrealized-dispatch-green-through-modelled-dispatch
  (testing "the delegation is realized once code reaches the target through the modelled dispatch point"
    (is (empty? (corr/unrealized-dispatch (dispatch-fixture true)))
        "op-a -> op-b realized via op-a -> dp -> (dispatch) h -> op-b")))

(deftest unrealized-dispatch-green-through-registry-dispatch
  (testing "realized when the dispatch point routes DIRECTLY to the target (registry flavor, no trailing call)"
    (let [db (-> (sub/create)
                 (d/db-with
                  [{:db/id -1 :structure/of :lib.code/Module :entity/id "A" :entity/name "A"}
                   {:db/id -2 :structure/of :lib.code/Module :entity/id "B" :entity/name "B"}
                   {:db/id -3 :structure/of :lib.code/Operation :entity/name "op-a"}
                   {:db/id -4 :structure/of :lib.code/Operation :entity/name "op-b"}
                   {:db/id -9 :structure/of :lib.code/Operation :entity/name "dp"}
                   {:rel/id "A|exposes|op-a" :rel/from -1 :rel/kind :exposes :rel/to -3}
                   {:rel/id "A|child|dp"     :rel/from -1 :rel/kind :child   :rel/to -9}
                   {:rel/id "B|exposes|op-b" :rel/from -2 :rel/kind :exposes :rel/to -4}
                   {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                   {:rel/id "dp|dispatches-to|op-b" :rel/from -9 :rel/kind :dispatches-to :rel/to -4}
                   {:db/id -5 :structure/of :lib.code/Module :entity/id "A" :entity/name "A" :val/extracted true}
                   {:db/id -6 :structure/of :lib.code/Module :entity/id "B" :entity/name "B" :val/extracted true}
                   {:db/id -7  :structure/of :lib.code/Operation :entity/name "op-a" :val/extracted true}
                   {:db/id -8  :structure/of :lib.code/Operation :entity/name "op-b" :val/extracted true}
                   {:db/id -11 :structure/of :lib.code/Operation :entity/name "dp"   :val/extracted true}
                   {:rel/id "Ax|child|op-a" :rel/from -5 :rel/kind :child :rel/to -7}
                   {:rel/id "Ax|child|dp"   :rel/from -5 :rel/kind :child :rel/to -11}
                   {:rel/id "Bx|child|op-b" :rel/from -6 :rel/kind :child :rel/to -8}
                   {:rel/id "op-a|calls|dp" :rel/from -7 :rel/kind :calls :rel/to -11}]))]
      (is (empty? (corr/unrealized-dispatch db))
          "op-a -> op-b realized via op-a -> dp -> (dispatch) op-b"))))

(deftest unrealized-dispatch-green-on-self-model
  (testing "every authored cross-module delegation is realized op-level (transitively, through dispatch) on the live model"
    (is (empty? (corr/unrealized-dispatch (pipeline/build-model "src")))
        "0 unrealized — incl. run/run-all via run-probe dispatch, and structure-form/instance-form via the target-expr helper chain")))

(deftest effect-correspondence-fires-on-an-undeclared-transitive-effect
  (testing "an authored op whose twin TRANSITIVELY reaches an effect it doesn't declare is flagged
            (the EffectCorrespondence under-declaration direction, over the recursive reaches-effect rule);
            declaring the effect on the authored op clears it"
    (let [io     {:db/id -10 :structure/of :lib.code/Effect :val/name "io"}
          ;; authored f (module m) ; extracted twin f (module fukan.m, corresponds to m) calls g ; g performs :io
          common [{:db/id -1 :structure/of :lib.code/Module :entity/name "m"}
                  {:db/id -2 :structure/of :lib.code/Operation :entity/name "f"}                          ; authored — declares nothing
                  {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -3 :structure/of :lib.code/Module :entity/name "fukan.m" :val/extracted true}   ; code module (corresponds to "m")
                  {:db/id -4 :structure/of :lib.code/Operation :entity/name "f" :val/extracted true}       ; twin of f
                  {:db/id -5 :structure/of :lib.code/Operation :entity/name "g" :val/extracted true}       ; f calls g
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}
                  {:rel/id "km|child|g" :rel/from -3 :rel/kind :child :rel/to -5}
                  {:rel/id "f|calls|g"  :rel/from -4 :rel/kind :calls :rel/to -5}                          ; f → g (transitive reach)
                  io
                  {:rel/id "g|performs|io" :rel/from -5 :rel/kind :performs :rel/to -10}]                  ; g performs :io
          undeclared-db (-> (sub/create) (d/db-with common))
          declared-db   (-> (sub/create) (d/db-with (conj common {:rel/id "af|performs|io" :rel/from -2 :rel/kind :performs :rel/to -10})))]
      (is (= #{"f"} (corr/undeclared-effects undeclared-db))
          "f's twin transitively reaches :io (via g), but f declares nothing → under-declaration")
      (is (empty? (corr/undeclared-effects declared-db))
          "declaring :io on the authored f satisfies EffectCorrespondence"))))

(deftest effect-and-totality-green-on-the-self-model
  (testing "the merged self-model declares every effect its code reaches, and its trusted core is total"
    (let [model (pipeline/build-model "src")]
      (is (empty? (corr/undeclared-effects model))
          "0 undeclared effects — design and extraction speak one effect language, to call-graph depth")
      (is (empty? (corr/totality-violations model))
          "0 totality violations — every trusted-core reader (its :in is the Model) is total"))))

(deftest lens-coverage-fires-on-an-orphan-reader
  (testing "an extracted probe reader (probe-X) with no declared Lens of the same focus is an offender;
            a reader whose Lens exists is covered, a non-probe op is ignored, and the DUAL (a Lens with
            no reader) is allowed — the law guards reader→lens only"
    (let [db (-> (sub/create)
                 (d/db-with
                  [{:db/id -1 :structure/of :lib.lens/Lens :entity/name "survey"}                              ; a declared focus
                   {:db/id -2 :structure/of :lib.lens/Lens :entity/name "purity"}                              ; a Lens with NO reader — allowed
                   {:db/id -3 :structure/of :lib.code/Operation :entity/name "probe-survey" :val/extracted true} ; covered by the survey Lens
                   {:db/id -4 :structure/of :lib.code/Operation :entity/name "probe-orphan" :val/extracted true} ; no Lens "orphan" → offender
                   {:db/id -5 :structure/of :lib.code/Operation :entity/name "run"          :val/extracted true}]))] ; not a probe-* reader → ignored
      (is (= #{"probe-orphan"} (corr/uncovered-readers db))
          "only the probe reader with no declared Lens is flagged; the covered reader, the non-probe op, and the reader-less Lens are not"))))

(deftest lens-coverage-green-on-the-self-model
  (testing "every bespoke probe reader has a declared Lens twin on the live build-model \"src\""
    (is (empty? (corr/uncovered-readers (pipeline/build-model "src")))
        "0 uncovered readers — every probe-X leaf's focus is declared as a Lens instrument")))
