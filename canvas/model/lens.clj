(ns canvas.model.lens
  "Self-spec: fukan's LENSES — the focuses over the model. Forward-looking (the tier is
   parked under `.paused/`). A lens names WHAT to attend to; it is cross-cutting, so the
   same focus feeds different acts — a probe reads through it (e.g. patterns → a view,
   drift → a trust signal), a projection renders through it. The probes that consume
   these lenses live in the `probe` view (interlocking). The overview's `Faculty
   \"Lens\"` is `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.lens :refer [Lens]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "lens"
      ;; focuses fed to reasoning probes (non-gating findings)
      (Lens "survey"      (focus "the whole model's structure"))
      (Lens "patterns"    (focus "recurring structures across the model"))
      (Lens "consistency" (focus "where contracts and structure align — or drift"))
      (Lens "tar-pit"     (focus "complexity hotspots — tangles worth attention"))
      ;; focuses fed to inspect probes (gating findings — trust verdicts)
      (Lens "integrity"   (focus "the model's structural integrity — laws, partitions"))
      (Lens "coverage"    (focus "spec ↔ code coverage"))
      (Lens "drift"       (focus "spec ↔ code divergence")))))
