(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-6 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary).
   Phase 2: cross-extension reference resolution.
   Phase 3: merge.
   Phase 4: structural validation (sub-phases 4a-4g). Gate G2 halts on errors.
   Phase 5: constraint evaluation (kernel-universal + project-shipped).
            Non-gating — violations are outputs.
   Phase 6: Clojure Target Analyzer — projects edges from spec primitives
            to Code.* artifacts with per-edge :validity. Non-gating."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]
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
  "Top-level build: Allium → Boundary → Phase 4 → Phase 5 → Phase 6. Returns
   the unified Model with :violations from Phase 4 (gating), Phase 5
   (non-gating), and :artifacts + :relation/projects edges from Phase 6.
   Raises on Gate G2 halt during Phase 4."
  [source-root]
  (let [m1 (-> (allium/load-source source-root)
               (boundary/load-source source-root))
        {:keys [model violations]} (phase4/run m1)
        m2 (-> model (assoc :violations violations) register-defaults)
        m3 (phase5/run m2)]
    (clj-analyzer/run m3 (project-defaults/fukan-on-fukan) source-root)))
