(ns canvas.project_layer.registry-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.project_layer.registry :as port]
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
    (is (contains? names "project_layer.registry") "module name present")
    (is (contains? names "IdiomRoute") "IdiomRoute record present")
    (is (contains? names "IdiomEntry") "IdiomEntry record present")
    (is (contains? names "Registry") "Registry record present")
    (is (contains? names "PureRegistration") "PureRegistration invariant present")
    (is (contains? names "IdentityRegistry") "IdentityRegistry invariant present")
    (is (contains? names "make_registry") "make_registry function present")
    (is (contains? names "with_idiom") "with_idiom function present")))
