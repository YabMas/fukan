(ns canvas.model.vocabulary-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.vocabulary :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "HasTagFollowsAncestors"))
    (is (contains? names "PrimitiveKindDescriptiveSurface"))
    (is (contains? names "make_tag_definition"))
    (is (contains? names "make_tag_application"))
    (is (contains? names "make_predicate_registration"))
    (is (contains? names "make_renderer_registration"))
    (is (contains? names "has_tag_with_ancestors"))))
