(ns canvas.vocab.code.plug-point
  "Code vocab — `PlugPoint`: a designed point of variation a Module OWNS for OTHERS to satisfy — the
   split-ownership seam (an SPI / dependency-inversion point). Its shape is a `Schema` (the signature
   satisfiers must match). The point of the concept: ownership is DECOUPLED from implementation — the
   owning module declares the plug-point and (as the SPI framework) dispatches THROUGH it, but does not
   fix its implementations; separate parties `satisfy` it, and the dependency INVERTS (satisfiers depend
   on the owner, the owner never names them). An ordinary API surface is NOT a plug-point — its op
   signature already IS its (implicit, adherence-gated) contract; `PlugPoint` is RESERVED for a designed
   point of variation whose implementations aren't fixed to one owner.

   COARSE first cycle — the concept captures only: what the plug-point is (`:shape`), who OWNS it
   (`Module :offers`), and who SATISFIES it (`Module :satisfies`). Deferred to a later cycle: HOW a
   plug-point is satisfied in Fukan — `satisfies` = *adheres* (the satisfier's signature adheres to the
   plug-point Schema, reusing the adherence machinery) + *registers* (the linkage — `register-X!` call /
   `defmethod` / convention, a correspondence-recognized realization, NOT baked into the concept) — and
   whether the plug-points are all equal or split into sub-patterns."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.typing :refer [Schema]]))

(defstructure PlugPoint
  "A designed point of variation a Module owns for others to satisfy — its `:shape` is the Schema (the
   signature) a satisfier must adhere to. Ownership decoupled from implementation: the defining module
   offers it and dispatches through it; separate parties fulfil it (the dependency inverts)."
  {:shape [:? Schema]})
