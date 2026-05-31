(ns fukan.model.pipeline
  "Build pipeline.

   Canvas specs (canvas/<subsystem>/<module>.clj) are the spec source. The
   pipeline runs:
     Phase 0: Canvas ingestion — build the unified Datascript db from the
              canvas specs, project to the model map (via canvas-source).
     Phase 6: Clojure Target Analyzer — projects edges from spec primitives
              to Code.* artifacts with per-edge :validity. Non-gating.

   Phase 4 (structural validation) and Phase 5 (constraint evaluation) are
   retired — they were the legacy verification story; the lean-kernel rebuild
   moves verifiable meaning onto datalog constraints carried by structures
   themselves. The retired code is recoverable from history."
  (:require [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.target.clojure.analyzer :as clj-analyzer]
            [fukan.project-layer.defaults :as project-defaults]))

(defn build-model
  "Top-level build: Phase 0 (canvas ingestion) → Phase 6 (Clojure analyzer).
   Returns {:model <Model> :canvas-db <Datascript db>}.

   The canvas db is the single source: Phase 6 transacts Code.* artifacts and
   :relation/projects edges into it (clj-analyzer/enrich-db), and the held
   Model derives its :artifacts and projects edges back out of the db
   (canvas-source/db->artifacts + db->projects-edges) — the map is a view, not
   a second source. Model carries :violations from the analyzer's
   duplicate-address lint."
  [source-root]
  (let [db0 (canvas-source/build-substrate)          ; Phase 0a: build + enrich (uses, stable-ids)
        m0  (canvas-source/project db0)              ; Phase 0b: structural map
        {db1 :db v :violations}                       ; Phase 6: analyzer transacts into the db
        (clj-analyzer/enrich-db db0 m0 (project-defaults/fukan-on-fukan)
                                source-root (canvas-source/stable->uuid-map db0))
        full (-> m0                                   ; held map derives Phase-6 content from db1
                 (assoc :violations v)
                 (assoc :artifacts (canvas-source/db->artifacts db1))
                 (update :edges into (canvas-source/db->projects-edges db1)))]
    {:model full :canvas-db db1}))
