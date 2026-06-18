(ns fukan.target.correspondence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            ;; loading infra.model is the composition root — it registers fukan's
            ;; malli dialect AND its Clojure extractor, and offers build/load of the model
            [fukan.infra.model :as infra-model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s]
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
    (let [db (-> (s/create)
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

(deftest uncovered-calls-lists-undeclared-couplings
  (testing "actual cross-module calls with no corresponding authored delegation are the worklist"
    (let [worklist (corr/uncovered-calls (pipeline/build-model "src"))]
      (is (seq worklist) "the self-model has real couplings not yet declared as :delegates")
      (is (every? (fn [[a b]] (not= a b)) worklist) "all entries are cross-module pairs"))))
