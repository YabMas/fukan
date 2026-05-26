(ns canvas.vocabulary.allium.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.vocabulary.allium.analyzer :as port]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-key-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "vocabulary.allium.analyzer") "module name present")
    (is (contains? names "ExposesIssue") "ExposesIssue record present")
    (is (contains? names "EventShapeMismatch") "EventShapeMismatch record present")
    (is (contains? names "DeclarationOrder") "invariant present")
    (is (contains? names "analyze_file") "analyze_file function present")
    (is (contains? names "extract_use_aliases") "extract_use_aliases function present")))
