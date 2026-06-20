(ns canvas.architecture.reading.correspondence
  "Self-spec: the MODEL↔CODE CORRESPONDENCE — drift and coverage as queries over the unified
   graph (`fukan.target.correspondence`). Realizes the `Lens` subject faculty (reads the graph
   for drift/coverage). The operations read the kernel's shared `StructureDb`; the type-drift
   reading additionally compares modelled vs realized signatures through the type dialect."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.typing :as typing]))

(Module target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  (Kind OperationName :string)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]})
  (Operation type-drifted-operations
    "Modelled operations whose type disagrees with the realizing function's declared signature."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]
     :delegates [typing/type-adheres?]})         ; compares modelled vs realized sigs via the dialect
  ;; ── the relation-level coverage/fidelity queries (the :delegates⟷:calls seam) ──
  (Operation unrealized-delegates "Authored cross-module delegations with no realizing actual call (intent→fact gap)."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})                 ; reads the registered CallRealization law via check
  (Operation unrealized-dispatch "Authored cross-module delegations not realized op-level even through a modelled dispatch point (transitive over :calls ∪ :dispatches-to)."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]})
  (Operation uncovered-calls "Actual cross-module calls with no covering :delegates — the fidelity coverage worklist."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector [:tuple :string :string]]]})
  (Operation unfaithful-calls "Extracted callers making an undeclared cross-module call between MODELLED faculties."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})                 ; reads the registered Fidelity law via check
  (Operation uncovered-public-operations "PUBLIC extracted operations with no model twin — the encapsulation worklist."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector OperationName]]})
  (Operation operation-sig "Render an authored Operation's modelled type to a malli function-schema."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:op-eid :int]] :any]}))
