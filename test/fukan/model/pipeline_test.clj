(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.model.pipeline :as pipeline]))

(defn- names-of [db tag]
  (set (map first (d/q '[:find ?n :in $ ?t
                         :where [?e :structure/of ?t] [?e :entity/name ?n]]
                       db tag))))

(deftest build-model-ingests-canvas-specs-into-a-structure-db
  (testing "the model is the merged structure db over the canvas/ defstructure specs"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "infra.model")
          "the canvas/infra/model spec is discovered and ingested")
      (is (= #{"load-model" "get-model" "refresh-model"} (names-of db :Function)))
      (is (= #{"Model" "Src"} (names-of db :Type)))
      (is (= 3 (count (d/q '[:find ?r :where [?r :rel/kind :gives]] db)))
          "each Function's :gives relation is reified into the substrate"))))
