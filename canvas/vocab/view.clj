(ns canvas.vocab.view
  "The fukan-on-fukan model's CROSS-VIEW layer — how the interlocking views map onto
   each other. `view-map` is a relation-COPRODUCT (a `defrelation-coproduct`): the union
   of the cross-view link relations the subsystem models already author —

     :via            collab `Phase` → overview `Faculty`     (purpose → concept)
     :realized-by    overview `Faculty` → subsystem `Module` (concept → implementation)
     :contextualizes projection `Projection` → its base      (within the projection view)

   So `(view-map ?a ?b)` reads at domain altitude over all three at once, and a transitive
   `traces` rule (authored in a law's :rules over this vocab-derived relation) composes a
   concept across views — e.g. the `Observe` phase ↦ `Probe` faculty ↦ the probe modules.

   Vocab-only canvas spec (no build-canvas): it declares grammar, ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defrelation-coproduct]]))

(defrelation-coproduct :view-map
  "The cross-view mapping: the union of the inter-view link relations."
  :via :realized-by :contextualizes)
