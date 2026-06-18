(ns canvas.architecture.reading.correspondence
  "Self-spec: the MODEL↔CODE CORRESPONDENCE — drift and coverage as queries over the unified
   graph (`fukan.target.correspondence`). Realizes the `Lens` subject faculty (reads the graph
   for drift/coverage). The operations read the kernel's shared `StructureDb`; the type-drift
   reading additionally compares modelled vs realized signatures through the type dialect."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.typing :as typing]
            [canvas.subject :as subj]))

(Module target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  {:realizes subj/Lens}                          ; faculty role: reads the graph (drift/coverage)
  (Kind OperationName :string)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]})
  (Operation type-drifted-operations
    "Modelled operations whose type disagrees with the realizing function's declared signature."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]
     :delegates [typing/type-adheres?]}))        ; compares modelled vs realized sigs via the dialect
