(ns fukan.model.pipeline-db-roundtrip-test
  "Step B golden equivalence: the held model map's Phase-6 content (artifacts +
   :relation/projects edges) must be identical whether produced the old way
   (analyzer assoc's onto the in-memory model) or the new way (analyzer
   transacts into the canvas db; the map derives back out via
   canvas-source/db->artifacts + db->projects-edges).

   If this passes, db-as-source is transparent to every downstream consumer
   (drift, coverage, edb, L1/L2, viewer), which all read the map."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.pipeline :as pipeline]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.project-layer.defaults :as project-defaults]))

(defn- norm-artifacts
  "Field iteration order is irrelevant (the only consumer treats :fields as a
   name→type map), so compare fields as sets."
  [artifacts]
  (into {}
        (map (fn [[k a]]
               [k (cond-> a
                    (get-in a [:sub :fields]) (update-in [:sub :fields] set))]))
        artifacts))

(defn- projects-edges
  "Projects edges reduced to their semantic identity (edge-level :id is not
   assigned by add-edge and read by no consumer)."
  [model]
  (->> (:edges model)
       (filter #(= :relation/projects (:kind %)))
       (map #(select-keys % [:kind :from :to :projection-kind :validity]))
       set))

(deftest phase6-db-roundtrip-matches-in-memory-projection
  (testing "db-derived Phase-6 content equals the analyzer's in-memory output"
    ;; Both representations must derive from the SAME m0 — the Phase 0 build
    ;; has run-to-run variance (symbol-walk collision ordering), so rebuilding
    ;; independently would compare two different models, not the round-trip.
    (let [db0 (canvas-source/build-canvas-db)
          m0  (canvas-source/project db0)
          reg (project-defaults/fukan-on-fukan)
          ;; in-memory: analyzer assoc's onto the model (the old shape)
          in-memory (analyzer/run m0 reg "src")
          ;; db path: analyzer transacts into db0; map derives back out
          {db1 :db} (analyzer/enrich-db db0 m0 reg "src"
                                        (canvas-source/stable->uuid-map db0))
          derived-artifacts (canvas-source/db->artifacts db1)
          derived-edges     (canvas-source/db->projects-edges db1)]
      (is (= (norm-artifacts (:artifacts in-memory))
             (norm-artifacts derived-artifacts))
          "db-derived artifacts equal the analyzer's in-memory artifacts")
      (is (= (projects-edges in-memory)
             (projects-edges {:edges derived-edges}))
          "db-derived projects edges equal the analyzer's in-memory edges")
      (is (pos? (count derived-artifacts))
          "fukan-on-fukan actually projects artifacts (guards against vacuous pass)"))))

(deftest retained-canvas-db-carries-phase6-datoms
  (testing "the retained canvas-db now carries artifact + reified projects datoms"
    (let [{:keys [canvas-db]} (pipeline/build-model "src")
          arts  (canvas-source/db->artifacts canvas-db)
          edges (canvas-source/db->projects-edges canvas-db)]
      (is (pos? (count arts)) "artifacts are queryable in the db")
      (is (pos? (count edges)) "projects edges are queryable in the db")
      (is (every? #(= :relation/projects (:kind %)) edges)))))
