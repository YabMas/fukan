(ns canvas.vocabulary.boundary.tags-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.boundary.tags :as port]
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
    (is (contains? names "vocabulary.boundary.tags") "module name present")
    (is (contains? names "BoundaryTagCatalogue") "BoundaryTagCatalogue record present")
    (is (contains? names "CatalogueIsExhaustive") "CatalogueIsExhaustive invariant present")
    (is (contains? names "RegistrationIsIdempotent") "RegistrationIsIdempotent invariant present")))
