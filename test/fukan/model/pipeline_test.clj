(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
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
      ;; subset, not =: more specs (e.g. canvas/pipeline) now contribute too
      (is (set/subset? #{"load-model" "get-model" "refresh-model"} (names-of db :Function)))
      (is (set/subset? #{"Model" "Src"} (names-of db :Type)))
      (is (<= 3 (count (d/q '[:find ?r :where [?r :rel/kind :gives]] db)))
          "each Function's :gives relation is reified into the substrate")
      (is (empty? (s/check db))
          "the whole self-model satisfies every structure's laws"))))

(deftest pipeline-self-spec-shares-value-identified-shapes
  (testing "the StructureDb type-shape recurs across stage signatures but is ONE node"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "model.pipeline")
          "the canvas/pipeline/model spec is ingested")
      (is (set/subset? #{"build-model" "ingest" "merge-dbs"} (names-of db :Stage)))
      ;; the StructureDb type-shape appears in 4 positions (merge-dbs in+out,
      ;; ingest out, build-model out) — value-identity collapses it to one node.
      ;; robust to other specs contributing shapes: assert the specific shared one
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :Shape] [?s :val/kind "type"]
                                    [?r :rel/from ?s] [?r :rel/kind :type]
                                    [?r :rel/to ?t] [?t :entity/name "StructureDb"]
                                    [?m :entity/name "model.pipeline"] [?m :module/child ?t]]
                           db)))
          "the StructureDb type-shape — used in four positions — is one value node"))))

(deftest canvas-source-model-shares-the-db-shape
  (testing "in the canvas_source self-model, the Db type-shape (4 uses) is one node"
    (let [db (pipeline/build-model "src")]
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :Shape] [?s :val/kind "type"]
                                    [?r :rel/from ?s] [?r :rel/kind :type]
                                    [?r :rel/to ?t] [?t :entity/name "Db"]
                                    [?m :entity/name "canvas-source"] [?m :module/child ?t]]
                           db)))
          "Db appears in db->entity-maps in, merge-dbs in (nested) + out, build out → one node"))))
