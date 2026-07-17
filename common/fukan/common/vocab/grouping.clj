(ns fukan.common.vocab.grouping
  "Structural primitives — the domain-agnostic building blocks the code vocab builds on: the
   `contains` membership genus, its first species `:child`, and `Grouping` (the most abstract
   membership primitive). LANGUAGE, not a specific design concept: any model groups things. Part of
   fukan's modelling vocabulary (`fukan.common.vocab/`); `code/module` is a `Grouping` that adds API +
   ownership.

   The membership RELATIONS live here as elements alongside the structure that first uses them — the
   kernel names none of them (until 2026-07-17 it hardcoded the `contains` union, its closure, and
   `in-module`, which is code vocabulary; `in-module` now rides `code/module`, its own element)."
  (:require [fukan.canvas.core.structure :refer [defstructure defrelation]]))

;; ── the membership relations, as ELEMENTS ────────────────────────────────────
;; A relation's CHARACTER — how it relates to other relations — is a property of the relation, so it
;; is declared ONCE here rather than repeated on every slot that uses it. Species join the genus with
;; `:isa`; a law over `contains` sees every species' edges for free, and `contains+` rolls the whole
;; ladder up.

(defrelation :contains
  "Membership — the GENUS. Not authored directly: a structure declares a slot of one of its SPECIES
   (`:child` here; `:exposes`/`:owns`/`:offers` on a code Module), and the species' `:isa` lifts
   those edges into `contains`. `:transitive` gives the `contains+` closure — containment rolls up a
   nesting ladder (a Subsystem contains+ the Operations of the Modules it clusters)."
  {:transitive true})

(defrelation :child
  "Internal membership — the ownership backbone: grain the container is source-of-truth for and no
   one else consumes. The most abstract containment species; a code Module refines it with
   surface-bearing siblings (`:exposes`/`:owns`/`:offers`)."
  {:isa :contains})

(defstructure Grouping
  "The most abstract grouping — a named bag of model instances, pure membership and nothing
   more. `:child` is a heterogeneous container (the `Any` wildcard) so a Grouping collects
   Operations, Kinds, Concepts, Faculties — whatever its members are. It carries no API or
   ownership semantics; a code `Module` is a Grouping that adds those.

   The slot names the `:child` RELATION and nothing more — containment is `:child`'s own character
   (declared above), not this structure's business, so every structure with a `:child` slot inherits
   it without restating it."
  {:child [:* Any]})
