(ns lib.grouping
  "Shared-lib vocabulary — the domain-agnostic structural primitives: `Module` (a named
   grouping) and `Connected` (the facet for a node that participates in the directed graph
   over its own kind). These are LANGUAGE, not fukan-specific domain concepts: any model
   groups things and any flow node is connected — so they live in the reusable `lib/`
   stdlib. (`lib.code`'s `Operation` includes `Connected`.)

   Opt-in (required, not auto-discovered like `canvas/**`); ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Module
  "A named grouping of model instances — the unit a subsystem view occupies. `:child`
   is a heterogeneous container (the `Any` wildcard) so a module groups Operations, Kinds,
   Concepts, Faculties — whatever its members are. `in-module` resolves over these
   `:child` relations (no privileged `:Module` tag in the kernel — a module is ordinary vocab)."
  (slot :child (many Any)))

(defstructure Connected
  "Facet: a node that participates in the directed graph over its own kind — it is not
   isolated (has some incoming or outgoing relation)."
  (law "no isolated node"
    :offenders '[?n]
    :where '[(not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))
