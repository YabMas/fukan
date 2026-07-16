(ns fukan.common.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace) and its correspondence to a code namespace.
   Module's own `(correspond Module …)` declares the bridged twin root: a canvas Module twins with its
   extracted code twin by the `:qualified-suffix` name-match strategy (the kernel's generic bridge
   lowering — canvas short-name is a separator-agnostic dotted suffix of the code namespace, so
   `infra-model` ← `fukan.infra.model` — with no hand-written CozoScript or name-bridge fn here).
   Operation's own correspondence (the fact-side slots, the twin, the
   call-realization demands + readers) lives with the element itself in
   `fukan.common.vocab.code.operation`. The module-dependency graph
   (`module-owns`/`module-depends`/`module-dependencies`) — a cross-module analysis consumed entirely
   by Subsystem's architecture laws — lives with those laws in `fukan.common.vocab.code.subsystem`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.plug-point :refer [PlugPoint]]))

(defstructure Module
  "A code module — one cohesion boundary (a namespace). Like a `Grouping` it collects members
   (`:child`), but it ALSO carries code semantics: an explicit API surface (`:exposes`) and the
   data-shapes it is the source of truth for (`:owns`). Conceptually a Module IS-A Grouping.

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the data-shapes
   that CROSS THE BOUNDARY — Kinds other modules ADOPT by name (and don't redefine); `:child` is the
   internal membership / ownership backbone (`in-module` resolves over `:exposes`/`:owns`/`:child`),
   the home for grain a module is source-of-truth-for but no one else consumes. The discriminant is
   adoption: a data-shape no other module names is internal grain (`:child`), not a boundary (`:owns`).

   PURE IDENTITY — Module is the ROOT of the correspondence twin ladder, but that (the name bridge)
   hooks in from OUTSIDE via `(correspond Module …)` below, not here."
  {:exposes   [:* {:contains true} Operation]  ; the public API surface — Operations callers depend on
   :owns      [:* {:contains true} Kind]       ; data-shapes that cross the boundary (other modules adopt by name)
   :offers    [:* {:contains true} PlugPoint]  ; plug-points it OWNS for others to satisfy (SPIs / dependency-inversion points)
   :satisfies [:* PlugPoint]                   ; plug-points it SATISFIES (owned elsewhere) — the inverted edge; NOT containment
   :child     [:* {:contains true} Any]})      ; internal members + grain no other module consumes

;; ── Module's own correspondence: the bridged twin root ────────────────────────
;; Module is the ROOT of the correspondence twin ladder — `(bridge :qualified-suffix)` pairs a canvas
;; Module with its extracted code twin by the kernel's name-match strategy (canvas short-name is a
;; separator-agnostic dotted suffix of the code namespace), and every Operation twin nests WITHIN a
;; twinned Module pair. Declared from outside the defstructure so the identity above stays pure.

(s/correspond Module (bridge :qualified-suffix))

