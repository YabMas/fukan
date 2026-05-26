(ns canvas.vocabulary.boundary.analyzer-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.boundary.analyzer :as port]
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
    (is (contains? names "vocabulary.boundary.analyzer") "module name present")
    (is (contains? names "BindingIssue") "BindingIssue record present")
    (is (contains? names "FileShapeDiscipline") "FileShapeDiscipline invariant present")
    (is (contains? names "ThreeFnForms") "ThreeFnForms invariant present")
    (is (contains? names "DeclareNewIntroducesOperation") "DeclareNewIntroducesOperation invariant present")
    (is (contains? names "AttachReusesOperation") "AttachReusesOperation invariant present")
    (is (contains? names "ExportsClosesModule") "ExportsClosesModule invariant present")
    (is (contains? names "SubsystemComposesChildren") "SubsystemComposesChildren invariant present")
    (is (contains? names "BindingEdgeIsBestEffort") "BindingEdgeIsBestEffort invariant present")
    (is (contains? names "Phase4StateAccumulation") "Phase4StateAccumulation invariant present")))
