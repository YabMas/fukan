(ns canvas.domain.lens
  "Self-spec: fukan's LENSES — the focuses over the model. A lens names WHAT to attend to
   (prose `:focus`). The executable selection that resolves a focus to a genuine sub-graph
   lives in the realization view (`canvas.realization.acts`, a `LensSelection`,
   evaluated by `core.lens/evaluate-lens`). A lens is cross-cutting: the same focus feeds
   different acts — a probe reads through it (patterns → a view, integrity → a trust signal),
   a projection renders through it (Blueprint, DriftClose). The probes that consume these
   lenses live in the `probe` view."
  (:require [canvas.vocabulary.act :refer [Lens]]
            [lib.grouping :refer [Grouping]]))

;; focuses fed to reasoning probes (non-gating findings) — prose only; the executable
;; selections live in canvas.realization.acts (LensSelection)
(Lens survey      {:focus "the whole model's structure"})
(Lens patterns    {:focus "recurring structures across the model"})
(Lens consistency {:focus "where contracts and structure align — or drift"})
(Lens tar-pit     {:focus "complexity hotspots — tangles worth attention"})
;; focuses fed to inspect probes (gating findings — trust verdicts)
(Lens integrity   {:focus "the model's structural integrity — laws, partitions"})
(Lens coverage    {:focus "spec ↔ code coverage"})
(Lens drift       {:focus "spec ↔ code divergence"})

(Grouping lens
  {:child [survey patterns consistency tar-pit integrity coverage drift]})
