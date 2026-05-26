(ns canvas.constraint.derivations-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.constraint.derivations :as port]
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
    (is (contains? names "constraint.derivations") "module name present")
    (is (contains? names "EDB") "EDB value type present")
    (is (contains? names "KernelUniversal") "KernelUniversal invariant present")
    (is (contains? names "ModuleDerivation") "ModuleDerivation invariant present")
    (is (contains? names "PureProjection") "PureProjection invariant present")
    (is (contains? names "model_to_edb") "model_to_edb function present")))
