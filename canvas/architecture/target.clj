(ns canvas.architecture.target
  "Self-spec: fukan's TARGET layer — how an analyzed codebase's structure is extracted INTO the
   Model, and how the Model's realization in that code is verified. Two namespaces, two subsystems:
   `target.clojure` (the clj-kondo extractor) and `target.correspondence` (drift / coverage queries).
   Realizes part of the subject's `Source` (the code-up extraction) and `Lens` (its correspondence
   reading); both read/produce the kernel's shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]
            [canvas.subject :as subj]))

(Module target-clojure
  "The Clojure extractor — reads source via clj-kondo (no eval) and emits code structures into a db."
  {:realizes subj/Source}                        ; faculty role: the code-up half of the Source in-fold
  (Kind Path :string)
  (Operation extract "Extract code structures from source paths into the shared StructureDb."
    {:signature [:=> [:catn [:paths [:vector Path]]] kernel/StructureDb]
     :performs  [:io]}))

(Module target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  {:realizes subj/Lens}                          ; faculty role: reads the graph (drift/coverage)
  (Kind OperationName :string)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]}))
