(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-4 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary parse independently).
   Phase 2: cross-extension reference resolution.
   Phase 3: merge (kernel content unioned by identity).
   Phase 4: structural validation (sub-phases 4a-4g).
            Gate G2 halts on errors > 0.

   Phase 5 (constraints) is Plan 4."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]
            [fukan.validation.phase4 :as phase4]))

(defn load-source
  "Top-level load: Allium → Boundary → Phase 4. Returns the unified Model
   with :violations attached (empty vector if Phase 4 produced none, or
   populated if there are warnings). Raises ex-info on Gate G2 halt
   (any errors)."
  [source-root]
  (let [m1 (-> (allium/load-source source-root)
               (boundary/load-source source-root))
        {:keys [model violations]} (phase4/run m1)]
    (assoc model :violations violations)))
