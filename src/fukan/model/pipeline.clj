(ns fukan.model.pipeline
  "Build pipeline (Phase 0 + 4-6 per DESIGN.md).

   Canvas specs (canvas/<subsystem>/<module>.clj) are the sole spec source
   as of Phase 3 Sprint 3. The legacy Allium/Boundary parse phases (1-3)
   have been retired. The pipeline runs:
     Phase 0: Canvas ingestion — build unified Datascript db from all 62
              canvas ports, project to model map (via canvas-source/build).
     Phase 4: structural validation (sub-phases 4a-4g). Gate G2 halts on errors.
     Phase 5: constraint evaluation (kernel-universal + project-shipped).
              Non-gating — violations are outputs.
     Phase 6: Clojure Target Analyzer — projects edges from spec primitives
              to Code.* artifacts with per-edge :validity. Non-gating."
  (:require [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.validation.phase4 :as phase4]
            [fukan.constraint.phase5 :as phase5]
            [fukan.constraint.well-known :as wk]
            [fukan.target.clojure.analyzer :as clj-analyzer]
            [fukan.project-layer.defaults :as project-defaults]))

(defn- register-defaults
  "Append fukan-shipped well-known constraints to :predicates. Idempotent —
   skips already-registered (namespace, name) tuples so repeat loads don't
   double-fire."
  [model]
  (let [existing (set (map (juxt :namespace :name) (:predicates model)))
        defaults [(wk/signal-gap)
                  (wk/external-must-have-wrapper)]
        new (remove (fn [r] (existing [(:namespace r) (:name r)])) defaults)]
    (update model :predicates (fnil into []) new)))

(defn build-model
  "Top-level build: Phase 0 (canvas ingestion) → Phase 4 → Phase 5 → Phase 6.
   Returns {:model <Model> :canvas-db <Datascript db>}, where Model carries
   :violations from Phase 4 (gating), Phase 5 (non-gating), and :artifacts +
   :relation/projects edges from Phase 6, and canvas-db is the unified Phase-0
   Datascript db (retained so trust/weigh queries reuse it rather than
   rebuilding it per call).
   Raises on Gate G2 halt during Phase 4.

   Phase 0 builds and projects all 62 canvas ports into the initial model map.
   Legacy Allium/Boundary parse phases have been retired."
  [source-root]
  (let [db (canvas-source/build-canvas-db)              ; Phase 0a: build unified db once
        m0 (canvas-source/project db)                   ; Phase 0b: project to model map
        {:keys [model violations]} (phase4/run m0)      ; Phase 4: structural validation
        m2 (-> model (assoc :violations violations) register-defaults)
        m3 (phase5/run m2)                               ; Phase 5: constraint evaluation
        model (clj-analyzer/run m3 (project-defaults/fukan-on-fukan) source-root)] ; Phase 6
    {:model model :canvas-db db}))
