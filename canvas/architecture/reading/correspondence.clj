(ns canvas.architecture.reading.correspondence
  "Self-spec: the MODEL↔CODE CORRESPONDENCE — drift and coverage as queries over the unified
   graph (`fukan.target.correspondence`). Realizes the `Lens` subject faculty (reads the graph
   for drift/coverage). Both operations read the kernel's shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.subject :as subj]))

(Module target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  {:realizes subj/Lens}                          ; faculty role: reads the graph (drift/coverage)
  (Kind OperationName :string)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]}))
