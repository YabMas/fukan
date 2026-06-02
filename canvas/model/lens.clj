(ns canvas.model.lens
  "Self-spec: fukan's LENS substrate — pluggable thinking-modes over the model.
   Forward-looking: the subsystem is parked (`.paused/`), so this models what the
   lens tier should be on the lean core, to inform its rebuild. Each lens weighs an
   aspect of the model and yields a view; adding a Lens is how a new thinking-mode
   plugs in. Interlocked with the top-level view: the overview's `Faculty \"Lens\"`
   is `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.lens :refer [Lens View]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "lens"
      (View "Survey"      (doc "A structural overview of the whole model."))
      (View "Patterns"    (doc "Recurring structural patterns across the model."))
      (View "Consistency" (doc "Where contracts and structure align — or drift."))
      (View "TarPit"      (doc "Complexity hotspots — tangles worth attention."))

      (Lens "survey"      (weighs "the whole model")              (yields Survey))
      (Lens "patterns"    (weighs "recurring structures")         (yields Patterns))
      (Lens "consistency" (weighs "contract/structure alignment") (yields Consistency))
      (Lens "tar-pit"     (weighs "complexity hotspots")          (yields TarPit)))))
