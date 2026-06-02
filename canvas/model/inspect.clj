(ns canvas.model.inspect
  "Self-spec: fukan's INSPECT subsystem — the trust tier over the model. Forward-
   looking (the subsystem is parked): the checks that verify the model's health,
   each raising a signal. Interlocked: the overview's `Faculty \"Inspect\"` is
   `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.inspect :refer [Check Signal]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "inspect"
      (Signal "IntegrityReport" (doc "Whether the model's structure holds together (laws, partitions)."))
      (Signal "CoverageReport"  (doc "How much of the target's code is covered by specifications."))
      (Signal "DriftReport"     (doc "Where specifications and code have diverged."))

      (Check "integrity" (inspects "the model's structural integrity")  (raises IntegrityReport))
      (Check "coverage"  (inspects "spec ↔ code coverage")              (raises CoverageReport))
      (Check "drift"     (inspects "spec ↔ code divergence")            (raises DriftReport)))))
