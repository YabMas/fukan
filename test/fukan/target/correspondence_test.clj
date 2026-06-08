(ns fukan.target.correspondence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            ;; loading infra.model is the composition root — it registers fukan's
            ;; malli dialect AND its Clojure extractor, and offers build/load of the model
            [fukan.infra.model :as infra-model]
            [fukan.dialect.malli :as malli]
            [fukan.model.typing :as typing]
            [fukan.target.correspondence :as corr]))

;; register the project dialect (malli render + sigs-adhere?) for the `type-adheres?` path
;; — per-test, since dialect registration is global mutable state other namespaces touch.
(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render :adheres? malli/sigs-adhere?}) (t)))

(deftest sigs-adhere-out-and-in-set
  (testing "adherence is OUT-equality AND IN-set-equality on malli function-schemas"
    (is (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                            '[:=> [:cat :Path] :StructureDb])
        "identical schemas adhere")
    (is (not (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                                 '[:=> [:cat :Str] :StructureDb]))
        "an input mismatch breaks adherence")
    (is (not (malli/sigs-adhere? '[:=> [:cat :Path] :StructureDb]
                                 '[:=> [:cat :Path] :Other]))
        "an output mismatch breaks adherence")
    (testing "inputs compared as a SET — order is not checked (documented v1 limitation)"
      (is (malli/sigs-adhere? '[:=> [:cat :A :B] :R]
                              '[:=> [:cat :B :A] :R])
          "reordered inputs still adhere (set semantics)"))))

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
