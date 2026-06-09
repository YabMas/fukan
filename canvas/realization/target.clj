(ns canvas.realization.target
  "Self-spec: fukan's TARGET layer — how an analyzed codebase's structure is extracted INTO the
   Model, and how the Model's realization in that code is verified. Two namespaces, two subsystems:
   `target.clojure` (the clj-kondo extractor) and `target.correspondence` (drift / coverage queries).
   Realizes the overview's `Target` faculty; both read/produce the kernel's shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Subsystem]]
            [canvas.realization.kernel :as kernel]))

(Subsystem target-clojure
  "The Clojure extractor — reads source via clj-kondo (no eval) and emits code structures into a db."
  (Kind Path)
  (Kind Analysis)
  (Operation ^:private analyze "Run clj-kondo over the paths."
    (signature [:=> [:catn [:paths [:vector Path]]] Analysis]) (performs :io))
  (Operation extract "Extract code structures from source paths into the shared StructureDb."
    (signature [:=> [:catn [:paths [:vector Path]]] kernel/StructureDb]) (performs :io)
    (calls analyze)))

(Subsystem target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  (Kind OperationName)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    (signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]])
    (calls kernel/check))
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    (signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]])))
