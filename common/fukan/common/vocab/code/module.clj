(ns fukan.common.vocab.code.module
  "Code vocab — `Module`: a code boundary, its containment species, and the membership relation
   derived from them. PURE DESIGN, language-neutral: nothing here knows what a namespace is.

   The design↔code CORRESPONDENCE — how a Module finds its code twin, and Operation's fact-slots and
   drift demands — maps into a specific language's constructs, so it rides that language's extractor
   (`fukan.common.extraction.clojure.{module,operation}`), not this vocabulary. The
   module-dependency graph (`module-owns`/`module-depends`/`module-dependencies`) — a cross-module
   analysis consumed entirely by Subsystem's architecture laws — lives with those laws in
   `fukan.common.vocab.code.subsystem`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure defrelation]]
            [fukan.common.vocab.grouping]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.patterns.plug-point :refer [PlugPoint]]))

;; ── Module's containment species + the relation derived from them ─────────────
;; Each is a SPECIES of the `contains` genus (`vocab/grouping`) — the `(:sub :contains)` inclusion,
;; declared once on the relation itself, so `in-module`/`contains+` and every law over the genus
;; pick them up without any structure restating it. The pattern-tier relations (`:offers`, and
;; `:satisfies` — the inverted edge, deliberately NOT a species) are ELEMENTS of the pattern's own
;; signature (`vocab/patterns/plug-point`); Module's slot map only USES their names.

(defrelation :exposes
  "The public API surface — the Operations callers depend on."
  (:sub :contains))

(defrelation :owns
  "Data-shapes that CROSS THE BOUNDARY — Kinds other modules adopt by name (and don't redefine)."
  (:sub :contains))

(defrelation :in-module
  "An entity `?e` is a member of the container named `?mname` — the containment genus read by name.
   The membership convenience every law and lens selection uses at domain altitude
   (`(Operation ?s) (in-module ?s \"…\")`).

   It reads `contains`, so it resolves over EVERY species (`:child`/`:exposes`/`:owns`/`:offers`) for
   free. Note it does not require `?m` to be a Module — any NAMED container resolves; the name is a
   historical narrowing, and widening it would silently change every consumer's meaning. Lives here,
   with the code vocabulary it names, rather than in the kernel (where it was hardcoded until
   2026-07-17 — the kernel ships no vocabulary, and a module is vocabulary)."
  '[?e ?mname]
  '[(contains ?m ?e) [?m :entity/name ?mname]])

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
  {:exposes   [:* Operation]   ; the public API surface — Operations callers depend on
   :owns      [:* Kind]        ; data-shapes that cross the boundary (other modules adopt by name)
   :offers    [:* PlugPoint]   ; plug-points it OWNS for others to satisfy (SPIs / dependency-inversion points)
   :satisfies [:* PlugPoint]   ; plug-points it SATISFIES (owned elsewhere) — the inverted edge; NOT containment
   :child     [:* Any]})       ; internal members + grain no other module consumes

;; ── correspondence: NOT here ─────────────────────────────────────────────────
;; Module is the ROOT of the correspondence twin ladder, but HOW a design Module finds its code twin
;; is a realization decision about a specific language's module construct — so it rides the extractor
;; for that language (`fukan.common.extraction.clojure.module`), not this vocabulary.

