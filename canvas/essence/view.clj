(ns canvas.essence.view
  "The fukan-on-fukan model's CROSS-VIEW layer — how the interlocking views map onto
   each other. `view-map` is a relation-COPRODUCT (a `defrelation-coproduct`): the union
   of the cross-view link relations the subsystem models already author —

     :via            collab `Phase` → overview `Faculty`     (purpose → concept)
     :realized-by    overview `Faculty` → subsystem `Module` (concept → implementation)
     :contextualizes projection `Projection` → its base      (within the projection view)

   So `(view-map ?a ?b)` reads at domain altitude over all three at once, and a transitive
   `traces` rule (authored in a law's :rules over this vocab-derived relation) composes a
   concept across views — e.g. the `Observe` phase ↦ `Probe` faculty ↦ the probe modules.

   It also hosts the MATERIALIZATION FUNCTORIALITY law: an internal dataflow `:feeds`
   between two realized faculties must land as a real connection between their realizing
   modules — the essence-view's flow, checked against the materialization-view's structure.

   Vocab-only canvas spec (no build-canvas): it declares grammar, ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defrelation-coproduct defstructure]]))

(defrelation-coproduct :view-map
  "The cross-view mapping: the union of the inter-view link relations."
  :via :realized-by :contextualizes)

(defstructure MaterializationFunctor
  "Law-host: the materialization functoriality. An internal dataflow `:feeds` from faculty A
   to B — where BOTH are realized — must materialize: some member of A's realizing module(s)
   relates to some member of B's (in either direction; `:feeds` and the realizing `:through`/
   `:calls` often point opposite ways). It surfaces drift where the essence claims a dataflow
   the subsystems don't realize. `:builds-on` (foundation) and `:supplies` (decoupled external
   input) are deliberately excluded — they materialize differently, not as a module link."
  (law "a dataflow feed materializes as a module connection"
    :scope :global
    :offenders '[?fa ?fb]
    :where '[(feeds ?fa ?fb)
             [?fa :structure/of :Faculty] [?fb :structure/of :Faculty]
             (realized-by ?fa ?_ma) (realized-by ?fb ?_mb)
             (not-join [?fa ?fb]
               (realized-by ?fa ?ma) (realized-by ?fb ?mb)
               [?cx :rel/kind :child] [?cx :rel/from ?ma] [?cx :rel/to ?x]
               [?cy :rel/kind :child] [?cy :rel/from ?mb] [?cy :rel/to ?y]
               (or-join [?x ?y]
                 (and [?r :rel/from ?x] [?r :rel/to ?y])
                 (and [?r2 :rel/from ?y] [?r2 :rel/to ?x])))]))
