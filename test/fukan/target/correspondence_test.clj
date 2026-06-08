(ns fukan.target.correspondence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fukan.dialect.malli :as malli]
            [fukan.model.typing :as typing]))

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
