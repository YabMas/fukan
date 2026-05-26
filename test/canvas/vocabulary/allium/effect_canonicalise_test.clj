(ns canvas.vocabulary.allium.effect-canonicalise-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.allium.effect-canonicalise :as port]
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
    (is (contains? names "vocabulary.allium.effect-canonicalise") "module name present")
    (is (contains? names "PatternEvaluationOrder") "PatternEvaluationOrder invariant present")
    (is (contains? names "SilentNonMatch") "SilentNonMatch invariant present")
    (is (contains? names "SourceBackreference") "SourceBackreference invariant present")
    (is (contains? names "PureMatching") "PureMatching invariant present")
    (is (contains? names "CompletenessForCanonicalShapes") "CompletenessForCanonicalShapes invariant present")))
