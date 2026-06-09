(ns canvas.domain.lens
  "Self-spec: fukan's LENSES — the focuses over the model. A lens names WHAT to attend to
   (prose `:focus`). The executable selection that resolves a focus to a genuine sub-graph
   lives in the realization view (`canvas.realization.acts`, a `LensSelection`,
   evaluated by `core.lens/evaluate-lens`). A lens is cross-cutting: the same focus feeds
   different acts — a probe reads through it (patterns → a view, integrity → a trust signal),
   a projection renders through it (Blueprint, DriftClose). The probes that consume these
   lenses live in the `probe` view."
  (:require [canvas.vocabulary.lens :refer [Lens]]
            [lib.grouping :refer [Grouping]]))

;; focuses fed to reasoning probes (non-gating findings) — prose only; the executable
;; selections live in canvas.realization.acts (LensSelection)
(def survey      (Lens (focus "the whole model's structure")))
(def patterns    (Lens (focus "recurring structures across the model")))
(def consistency (Lens (focus "where contracts and structure align — or drift")))
(def tar-pit     (Lens (focus "complexity hotspots — tangles worth attention")))
;; focuses fed to inspect probes (gating findings — trust verdicts)
(def integrity   (Lens (focus "the model's structural integrity — laws, partitions")))
(def coverage    (Lens (focus "spec ↔ code coverage")))
(def drift       (Lens (focus "spec ↔ code divergence")))

(def lens
  (Grouping (child survey patterns consistency tar-pit integrity coverage drift)))
