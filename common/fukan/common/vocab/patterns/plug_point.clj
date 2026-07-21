(ns fukan.common.vocab.patterns.plug-point
  "Pattern vocab — `PlugPoint`: a designed point of variation a Module OWNS for OTHERS to satisfy — the
   split-ownership seam (an SPI / dependency-inversion point). Its shape is a `Schema` (the signature
   satisfiers must match). The point of the concept: ownership is DECOUPLED from implementation — the
   owning module declares the plug-point and (as the SPI framework) dispatches THROUGH it, but does not
   fix its implementations; separate parties `satisfy` it, and the dependency INVERTS (satisfiers depend
   on the owner, the owner never names them). An ordinary API surface is NOT a plug-point — its op
   signature already IS its (implicit, adherence-gated) contract; `PlugPoint` is RESERVED for a designed
   point of variation whose implementations aren't fixed to one owner.

   THE PATTERN TIER (`vocab/patterns/`) — one rung above the core code grammar
   (`vocab/code/{kind,effect,operation,module,subsystem}`): a pattern is a named CONFIGURATION built on
   the core elements, not a core element itself. In the theory frame this file is an ENRICHMENT — a
   theory extension that imports the core's sorts and adds its own relations over them — so the
   relations the pattern adds (`:offers`/`:satisfies`) are declared HERE, in the pattern's signature,
   not the core's. KNOWN RESIDUAL: the SLOTS still ride `Module`'s slot map (and `module.clj` requires
   this ns for the target sort) — slots are the only authoring surface for a structure's edges, and an
   enrichment declaration shape (contributing slots from outside, the way `(correspond …)` contributes
   correspondence) waits for a second pattern-level element to press it out.

   COARSE first cycle — the concept captures only: what the plug-point is (`:shape`), who OWNS it
   (`Module :offers`), and who SATISFIES it (`Module :satisfies`). Deferred to a later cycle: HOW a
   plug-point is satisfied in Fukan — `satisfies` = *adheres* (the satisfier's signature adheres to the
   plug-point Schema, reusing the adherence machinery) + *registers* (the linkage — `register-X!` call /
   `defmethod` / convention, a correspondence-recognized realization, NOT baked into the concept) — and
   whether the plug-points are all equal or split into sub-patterns."
  (:require [fukan.canvas.core.structure :refer [defstructure defrelation]]
            [fukan.common.vocab.grouping]
            [fukan.common.typing.malli :refer [Schema]]))

;; ── the pattern's relations, owned by the pattern's signature ────────────────
;; `Module`'s slot map USES these names; the elements — the relations themselves, their inclusions and
;; docs — belong to the enriching theory, so they are declared here.

(defrelation :offers
  "Plug-points a module OWNS for others to satisfy (SPIs / dependency-inversion points) — a
   containment species (`(:sub :contains)`): an offered plug-point is a member of its owner."
  (:sub :contains))

(defrelation :satisfies
  "Plug-points a module SATISFIES (owned elsewhere) — the INVERTED edge of the pattern (the satisfier
   depends on the owner), deliberately NOT a `contains` species: satisfying a plug-point is not
   membership.")

(defstructure PlugPoint
  "A designed point of variation a Module owns for others to satisfy — its `:shape` is the Schema (the
   signature) a satisfier must adhere to. Ownership decoupled from implementation: the defining module
   offers it and dispatches through it; separate parties fulfil it (the dependency inverts)."
  {:shape [:? Schema]})
