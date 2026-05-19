(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-5 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary).
   Phase 2: cross-extension reference resolution.
   Phase 3: merge.
   Phase 4: structural validation (sub-phases 4a-4g). Gate G2 halts on errors.
   Phase 5: constraint evaluation (kernel-universal + project-shipped).
            Non-gating — violations are outputs."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]
            [fukan.validation.phase4 :as phase4]
            [fukan.constraint.phase5 :as phase5]
            [fukan.constraint.well-known :as wk]))

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

(defn load-source
  "Top-level load: Allium → Boundary → Phase 4 → Phase 5. Returns the
   unified Model with :violations from both Phase 4 (gating) and Phase 5
   (non-gating). Raises on Gate G2 halt during Phase 4."
  [source-root]
  (let [m1 (-> (allium/load-source source-root)
               (boundary/load-source source-root))
        {:keys [model violations]} (phase4/run m1)
        m2 (-> model (assoc :violations violations) register-defaults)]
    (phase5/run m2)))
