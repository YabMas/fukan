(ns fukan.common.vocab.code.module
  "Code vocab — `Module`: a code boundary — a Grouping over CODE ELEMENTS. PURE DESIGN,
   language-neutral: nothing here knows what a namespace is.

   The design↔code CORRESPONDENCE — how a Module finds its code twin — maps into a specific
   language's constructs, so it rides that language's extractor
   (`fukan.common.extraction.clojure.module`), not this vocabulary. The module-dependency graph
   (`module-owns`/`module-depends`/`module-dependencies`) — a cross-module analysis consumed
   entirely by Subsystem's architecture laws — lives with those laws in
   `fukan.common.vocab.code.subsystem`."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.vocab.grouping]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]))

(defstructure Module
  "A code module — one cohesion boundary (a namespace): a Grouping whose members are CODE
   ELEMENTS — Operations, Kinds, and (sub)Modules, nothing else. The union IS the membership
   constraint (its generated target-type law is the teeth — never a hand-written membership law),
   and membership is ONE relation: `:child`, the containment species declared in `vocab/grouping`.
   The fact vocabulary models a namespace the same way (`Ns {:child [:* Fn]}`, visibility a member
   fact) — the two strata are symmetric.

   There is no authored surface or boundary ROLE (the `:exposes`/`:owns` species were cut
   2026-07-21 — nothing consumed them): fukan models SURFACES. A modelled Operation corresponds
   to a PUBLIC code correspondent — the bridge `Operation :eq [Fn :public]` enforces exactly this — and
   interior helpers stay unmodelled (the delegates roll-up routes THROUGH them as ¬public
   interior). A boundary data-shape needs no flag either: adoption is readable from the graph
   (another module's signatures naming the Kind — `module-depends`' data-adoption clause).

   PURE IDENTITY — Module is the ROOT of the correspondence twin ladder, but its carrier
   relation hooks in from OUTSIDE via `(correspond Module …)` in the language extractor. The
   pattern tier above (`vocab/patterns/`) also names its own participation (`PlugPoint :owner`);
   this file is CLOSED to the tiers above it."
  {:child [:* Operation Kind Module]})
