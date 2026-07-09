(ns canvas.vocab.grouping
  "Structural primitives — the domain-agnostic building blocks the code vocab builds on: `Grouping`
   (the most abstract membership primitive). LANGUAGE, not a specific design concept: any model groups
   things. Part of fukan's modelling vocabulary (`canvas/vocab/`); `code/module` is a `Grouping` that
   adds API + ownership."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Grouping
  "The most abstract grouping — a named bag of model instances, pure membership and nothing
   more. `:child` is a heterogeneous container (the `Any` wildcard) so a Grouping collects
   Operations, Kinds, Concepts, Faculties — whatever its members are. It carries no API or
   ownership semantics; a code `Module` is a Grouping that adds those. `in-module` resolves
   over these `:child` relations (no privileged `:Grouping` tag in the kernel — a grouping is
   ordinary vocab)."
  {:child [:* {:contains true} Any]})
