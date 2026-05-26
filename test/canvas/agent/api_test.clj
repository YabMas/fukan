(ns canvas.agent.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.api :as port]
            [fukan.canvas.core.helpers :as h]
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
    (testing "module present"
      (is (contains? names "agent.api")))
    (testing "value types present"
      (is (contains? names "Envelope"))
      (is (contains? names "PrimitiveSummary"))
      (is (contains? names "RelationRow"))
      (is (contains? names "ArtifactSummary"))
      (is (contains? names "VocabularyEntry"))
      (is (contains? names "PrimitiveKindEntry"))
      (is (contains? names "RelationKindEntry"))
      (is (contains? names "SchemaSummary"))
      (is (contains? names "DriftRow"))
      (is (contains? names "Neighborhood"))
      (is (contains? names "CoverageReport"))
      (is (contains? names "Endpoint"))
      (is (contains? names "Violation"))
      (is (contains? names "Primitive")))
    (testing "invariants present"
      (is (contains? names "ReadOnlyQueries"))
      (is (contains? names "EnvelopePagination"))
      (is (contains? names "LayerDiscipline"))
      (is (contains? names "ModelLoadedPrecondition"))
      (is (contains? names "FilterRejection")))
    (testing "L0 fn present"
      (is (contains? names "q")))
    (testing "L1 probes present"
      (is (contains? names "primitives"))
      (is (contains? names "get_primitive"))
      (is (contains? names "relations"))
      (is (contains? names "vocabulary"))
      (is (contains? names "schema"))
      (is (contains? names "artifacts"))
      (is (contains? names "idioms"))
      (is (contains? names "constraints"))
      (is (contains? names "violations")))
    (testing "L2 views present"
      (is (contains? names "drift"))
      (is (contains? names "neighborhood"))
      (is (contains? names "coverage")))))
