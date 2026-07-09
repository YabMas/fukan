(ns canvas.vocab.code.contract
  "Code vocab — `Contract`: a named contract a Module OWNS for OTHER ops to implement — the
   split-ownership seam (a plug-point / SPI / dependency-inversion point). Its shape is a `Schema`
   (the signature implementers must match). The point of the concept: ownership is DECOUPLED from
   implementation — the owning module defines the contract but does not (necessarily) implement it;
   separate Operations `satisfies` it, and the dependency INVERTS (implementers depend on the owner,
   the owner never names them). An ordinary API surface is NOT a Contract — its op signature already
   IS its (implicit, adherence-gated) contract; `Contract` is RESERVED, and AUTHORED as a designation,
   for a designed point of variation whose implementations aren't fixed to one owner.

   COARSE first cycle — the concept captures only: what the contract is (`:shape`), who OWNS it
   (`Module :offers`), and who SATISFIES it (`Operation :satisfies`). Deferred to a later cycle: HOW a
   contract is satisfied in Fukan — `satisfies` = *adheres* (the satisfier's signature adheres to the
   contract Schema, reusing the adherence machinery) + *registers* (the linkage — `register-X!` call /
   `defmethod` / convention, a correspondence-recognized realization, NOT baked into the concept) — and
   whether the plug-points are all equal or split into sub-patterns."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocab.type :refer [Schema]]))

(defstructure Contract
  "A named contract a Module owns for others to implement — its `:shape` is the Schema (the signature)
   an `Operation` must adhere to to `satisfies` it. Ownership decoupled from implementation: the
   defining module offers it; separate operations fulfil it (the dependency inverts)."
  {:shape [:? Schema]})
