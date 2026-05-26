(ns canvas.model.type-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.type :as port]
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
    (is (contains? names "ConstructorsProduceType"))
    (is (contains? names "TargetLanguageNeutral"))
    (is (contains? names "make_scalar"))
    (is (contains? names "make_enum"))
    (is (contains? names "make_composite_named"))
    (is (contains? names "make_composite_inline"))
    (is (contains? names "make_collection"))
    (is (contains? names "make_union"))
    (is (contains? names "make_ref_kernel_primitive"))
    (is (contains? names "make_ref_substrate"))))
