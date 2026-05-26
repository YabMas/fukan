(ns canvas.constraint.ast-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.constraint.ast :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "constraint.ast") "module name present")
    (is (contains? names "Term") "Term value type present")
    (is (contains? names "ConstraintAtom") "ConstraintAtom value type present")
    (is (contains? names "Negation") "Negation value type present")
    (is (contains? names "Comparison") "Comparison value type present")
    (is (contains? names "Aggregation") "Aggregation value type present")
    (is (contains? names "ConstraintRule") "ConstraintRule value type present")
    (is (contains? names "PlainData") "PlainData invariant present")
    (is (contains? names "VariableConvention") "VariableConvention invariant present")
    (is (contains? names "is_var") "is_var function present")
    (is (contains? names "is_constant") "is_constant function present")
    (is (contains? names "make_atom") "make_atom function present")
    (is (contains? names "make_rule") "make_rule function present")
    (is (contains? names "vars_in_body") "vars_in_body function present")))
