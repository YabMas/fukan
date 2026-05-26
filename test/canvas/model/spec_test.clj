(ns canvas.model.spec-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.spec :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-core-type-vocabulary
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "Type"))
    (is (contains? names "ScalarType"))
    (is (contains? names "EnumType"))
    (is (contains? names "Field"))
    (is (contains? names "Parameter"))
    (is (contains? names "Expression"))
    (is (contains? names "Effect"))
    (is (contains? names "Model"))))

(deftest canvas-build-has-kernel-invariants
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "SubstratePrinciple"))
    (is (contains? names "ClausesAreFirstClass"))
    (is (contains? names "EffectExpressionParity"))
    (is (contains? names "StubResolutionIsUnconditional"))
    (is (contains? names "TriggersIsOccurrenceCausation"))
    (is (contains? names "ProjectsIsSpecToRealisation"))
    (is (contains? names "PayloadSchemaExtension"))
    (is (contains? names "NoInversePairs"))
    (is (contains? names "NamedClosedIncludedFloor"))))

(deftest canvas-build-has-endpoint-and-vocab-records
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "TagRef"))
    (is (contains? names "ArtifactIdentity"))
    (is (contains? names "SourceLocation"))
    (is (contains? names "PathSegment"))
    (is (contains? names "RelationalSpec"))))
