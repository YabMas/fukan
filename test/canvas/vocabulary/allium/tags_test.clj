(ns canvas.vocabulary.allium.tags-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.allium.tags :as port]
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
    (is (contains? names "vocabulary.allium.tags") "module name present")
    (is (contains? names "AlliumTagCatalogue") "AlliumTagCatalogue record present")
    (is (contains? names "CatalogueIsExhaustive") "CatalogueIsExhaustive invariant present")
    (is (contains? names "RegistrationIsIdempotent") "RegistrationIsIdempotent invariant present")
    (is (contains? names "TrustedTargetAssignment") "TrustedTargetAssignment invariant present")))
