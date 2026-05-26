(ns canvas.model.primitives-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.primitives :as port]
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
    (is (contains? names "ConstructorsProduceSubstrate"))
    (is (contains? names "KindIsAttached"))
    (is (contains? names "IdentityIsDeterministic"))
    (is (contains? names "make_field"))
    (is (contains? names "make_parameter"))
    (is (contains? names "make_definition"))
    (is (contains? names "make_rule_body"))
    (is (contains? names "make_container"))
    (is (contains? names "make_rule"))
    (is (contains? names "make_event"))))
