(ns fukan.common.vocab.grouping
  "Structural primitives — the domain-agnostic building blocks the code vocab builds on: the
   `contains` membership genus, its first species `:child`, and `Grouping` (the most abstract
   membership primitive). LANGUAGE, not a specific design concept: any model groups things. Part of
   fukan's modelling vocabulary (`fukan.common.vocab/`); `code/module` is a `Grouping` that adds API +
   ownership.

   The membership RELATIONS live here as elements alongside the structure that first uses them — the
   kernel names none of them (until 2026-07-17 it hardcoded the `contains` union, its closure, and
   the by-name membership reading — now the `:within` element below)."
  (:require [fukan.canvas.core.structure :refer [defstructure defrelation]]))

;; ── the membership relations, as ELEMENTS ────────────────────────────────────
;; A relation's INCLUSIONS — how it relates to other relations — are a property of the relation, so
;; they are declared ONCE here rather than repeated on every slot that uses it. A species states
;; `(:sub :contains)`, so a law over the genus sees every species' edges for free; the `contains+`
;; closure needs no declaration at all — closures are the compiler's, minted for every relation and
;; injected only where referenced.

(defrelation :contains
  "Membership — the GENUS, a bare element. Not authored directly: a structure declares a slot of a
   SPECIES (`:child`), and the species' `(:sub :contains)` inclusion lifts those edges into
   `contains`. `contains+` rolls a nesting ladder up (a Subsystem contains+ the Operations of the
   Modules it clusters).")

(defrelation :child
  "Membership — the ownership backbone: what the container collects and is the home of. The one
   containment species; a structure narrows WHO may be a member by its slot's target (a code
   Module's `:child` takes the union of code-element sorts, a Subsystem's takes Modules)."
  (:sub :contains))

(defrelation :within
  "An entity `?e` is a member of the container named `?cname` — the `contains` genus read by name,
   the membership convenience every law and lens selection uses at domain altitude
   (`(Operation ?s) (within ?s \"…\")`). It reads `contains`, so it resolves over EVERY species
   (`:child`) for free, over any NAMED container.
   Pure grouping vocabulary: its body mentions only the genus and the substrate's `:entity/name` —
   no code-vocab sort — so it lives here with the genus. (Until 2026-07-21 it was `in-module` in
   `code/module` — a historical narrowing: nothing about it requires a Module.)"
  [?e ?cname]
  [(contains ?c ?e) [?c :entity/name ?cname]])

(defstructure Grouping
  "The most abstract grouping — a named bag of model instances, pure membership and nothing
   more. `:child` is a heterogeneous container (the `Any` wildcard) so a Grouping collects
   Operations, Kinds, Concepts, Faculties — whatever its members are. It carries no API or
   ownership semantics; a code `Module` is a Grouping that adds those.

   The slot names the `:child` RELATION and nothing more — containment is `:child`'s own character
   (declared above), not this structure's business, so every structure with a `:child` slot inherits
   it without restating it."
  {:child [:* Any]})
