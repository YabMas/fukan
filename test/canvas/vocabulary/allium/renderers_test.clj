(ns canvas.vocabulary.allium.renderers-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.allium.renderers :as port]
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
    (is (contains? names "vocabulary.allium.renderers") "module name present")
    (is (contains? names "NodeIconCoverage") "NodeIconCoverage invariant present")
    (is (contains? names "IconNameLowercase") "IconNameLowercase invariant present")
    (is (contains? names "NonRoleTagsHaveNoNodeTreatment") "NonRoleTagsHaveNoNodeTreatment invariant present")
    (is (contains? names "RegistrationIsIdempotent") "RegistrationIsIdempotent invariant present")))
