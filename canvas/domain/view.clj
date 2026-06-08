(ns canvas.domain.view
  "The fukan-on-fukan model's CROSS-VIEW layer — how the interlocking perspectives map onto
   each other. `view-map` is a relation-COPRODUCT (a `defrelation-coproduct`): the union of
   the cross-view link relations the perspectives already author —

     :via            collab `Phase` → overview `Faculty`   (purpose → concept)
     :contextualizes projection `Projection` → its base    (within the projection view)

   So `(view-map ?a ?b)` reads at domain altitude over both at once, and a transitive
   `traces` rule (authored in a law's :rules over this vocab-derived relation) composes a
   concept across views.

   (The model↔code materialization is NOT a view-map member — it is the correspondence
   concern, kept off the domain in `canvas.materialize.correspondence`.)

   Vocab-only canvas spec (no build-canvas): it declares grammar, ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defrelation-coproduct]]))

(defrelation-coproduct :view-map
  "The cross-view mapping: the union of the inter-view link relations."
  :via :contextualizes)
