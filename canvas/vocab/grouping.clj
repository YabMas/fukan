(ns canvas.vocab.grouping
  "Structural primitives — the domain-agnostic building blocks the code vocab builds on:
   `Grouping` (a named grouping) and `Connected` (the facet for a node that participates in
   the directed graph over its own kind). These are LANGUAGE, not specific design concepts:
   any model groups things and any flow node is connected. Part of fukan's modelling
   vocabulary (`canvas/vocab/`); `code/module` is a `Grouping` that adds API + ownership."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Grouping
  "The most abstract grouping — a named bag of model instances, pure membership and nothing
   more. `:child` is a heterogeneous container (the `Any` wildcard) so a Grouping collects
   Operations, Kinds, Concepts, Faculties — whatever its members are. It carries no API or
   ownership semantics; a code `Module` is a Grouping that adds those. `in-module` resolves
   over these `:child` relations (no privileged `:Grouping` tag in the kernel — a grouping is
   ordinary vocab)."
  {:child [:* Any]})

(defstructure Connected
  "Facet: a node that participates in the directed graph over its own kind — it is not
   isolated (has some incoming or outgoing relation)."
  (law "no isolated node"
    :offenders '[?n]
    :where '[(not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))
