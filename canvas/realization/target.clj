(ns canvas.realization.target
  "Self-spec: fukan's TARGET layer — how an analyzed codebase's structure is extracted INTO the
   Model, and how the Model's realization in that code is verified. Two namespaces, two subsystems:
   `target.clojure` (the clj-kondo extractor) and `target.correspondence` (drift / coverage queries).
   Realizes the subject's `extract` Source and `correspondence`; both read/produce the kernel's shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.realization.kernel :as kernel]))

(Module target-clojure
  "The Clojure extractor — reads source via clj-kondo (no eval) and emits code structures into a db."
  (Kind Path)
  (Operation extract "Extract code structures from source paths into the shared StructureDb."
    {:signature [:=> [:catn [:paths [:vector Path]]] kernel/StructureDb]
     :performs  [:io]}))

(Module target-correspondence
  "The model↔code correspondence — drift and coverage as queries over the unified graph."
  (Kind OperationName)
  (Operation drifted-operations "Modelled operations with no realizing function (spec→code gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]
     :delegates [kernel/check]})
  (Operation uncovered-operations "Extracted operations with no model (code→spec gaps)."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector OperationName]]}))
