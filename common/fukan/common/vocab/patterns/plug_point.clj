(ns fukan.common.vocab.patterns.plug-point
  "Pattern vocab — `PlugPoint`: a CONTRACT — a signature with constraints on who defines/owns it and
   who has to adopt it, plus an expectation about how the indirection works. The owning module
   declares the plug-point and (as the SPI framework) dispatches THROUGH it, but does not fix its
   implementations; separate parties satisfy it, and the dependency INVERTS (satisfiers depend on
   the owner, the owner never names them). An ordinary API surface is NOT a plug-point — its op
   signature already IS its (implicit, adherence-gated) contract; `PlugPoint` is RESERVED for a
   designed point of variation whose implementations aren't fixed to one owner.

   THE PATTERN TIER (`vocab/patterns/`) — one rung above the core code grammar
   (`vocab/code/{kind,effect,operation,module,subsystem}`): a pattern is a named CONFIGURATION drawn
   OVER the core elements — a theory extension that imports the core's sorts and adds its own sort
   and relations. The dependency points strictly UPWARD: the pattern names its participants
   (`:owner`), the core never names the pattern — `Module` carries no pattern slots and does not
   require this ns. The domain-altitude reading `(offers ?m ?p)` is DERIVED (the converse of
   `:owner`), so laws and lenses still say \"module M offers P\" without Module knowing.

   COARSE first cycle — the concept captures only: what the plug-point is (`:shape`) and who OWNS it
   (`:owner`; at-most-one is the slot's cardinality, not a law). Deferred to a later cycle: the
   SATISFY side — *adheres* (the satisfier's signature adheres to the plug-point Schema, reusing the
   adherence machinery) + *registers* (the linkage — `register-X!` call / `defmethod` / convention,
   likely a correspondence-RECOGNIZED realization rather than an authored edge) — and whether the
   plug-points are all equal or split into sub-patterns. The former `:satisfies` slot (never used,
   semantics undesigned) was cut with the closure of `Module`; it re-enters with that cycle."
  (:require [fukan.canvas.core.structure :refer [defstructure defrelation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.typing.malli :refer [Schema]]))

(defstructure PlugPoint
  "A contract a Module owns for others to satisfy — its `:shape` is the Schema (the signature) a
   satisfier must adhere to, its `:owner` the module that defines it and dispatches through it.
   Ownership decoupled from implementation: separate parties fulfil it (the dependency inverts)."
  {:shape [:? Schema]
   :owner [:? Module]})

(defrelation :offers
  "Module ?m offers plug-point ?p — the derived CONVERSE of `:owner`, the domain-altitude reading
   (\"module M offers P\") the pattern provides so the core never has to."
  [?m ?p]
  [(owner ?p ?m)])
