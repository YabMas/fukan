(ns canvas.principles.operation-surface
  "Adopted principle — fukan's DEMANDS on the authored Operation surface (what we require of it, not
   what it IS). Two disciplines, held here rather than on Operation's identity defstructure:

   - COMPLETE OUTPUT TYPING (`types are absolute`): every PUBLIC authored Operation declares an output
     type. Absolute, no opt-out — down to `:nil` (side-effecting) or `:any` (genuinely dynamic); a
     missing `:out` is an undeclared contract, never a legitimate abstention. Output, not full signature:
     a nullary op legitimately has no `:in`, so the output is the part every op has and the part the
     public contract turns on.
   - NO DEAD OPERATION (no isolated node): every Operation participates in the graph — it is reached or
     it reaches (has some incoming or outgoing relation). A wholly-isolated op is dead.

   The ns IS the bundle (no `Principle` structure minted); `OperationSurface` is a pure law-holder (no
   slots, no instances) — the demands are `:scope :global` and bind their subject explicitly to
   `(Operation …)`, since the offenders are Operations, not instances of the holder."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure OperationSurface
  "Law-holder for the authored-operation-surface demands — carries no slots or instances of its own."
  (law "every public authored operation declares an output type"
    :key   :signature-completeness
    :scope :global
    :offenders '[?x]
    :where '[(Operation ?x) (design ?x) (exposed ?x)
             (not-join [?x] (out ?x ?_o))])
  (law "no isolated operation — every operation participates in the graph"
    :scope :global
    :offenders '[?n]
    :where '[(Operation ?n) (not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))
