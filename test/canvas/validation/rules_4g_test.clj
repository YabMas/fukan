(ns canvas.validation.rules-4g-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.validation.rules-4g :as port]
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
    (is (contains? names "validation.rules-4g") "module name present")
    (is (contains? names "check") "checker present")
    (is (contains? names "CrossModuleReferencesAreVisible") "cross-module invariant present")
    (is (contains? names "IntraModuleReferencesIgnored") "intra-module invariant present")
    (is (contains? names "CheckIsPure") "purity invariant present")))
