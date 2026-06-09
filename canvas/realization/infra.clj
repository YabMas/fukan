(ns canvas.realization.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled with the
   materialize code vocab. It exposes the model-lifecycle API (load/get/refresh); the model it
   produces is the kernel's shared `StructureDb` (the domain `Model` faculty's data realization)
   and the source path is the shared `extraction/Path` — both referenced, not redeclared.

   Authored as one nested `Subsystem` form: the operations live inside it (no separate `def`s),
   each interned as a var by the def-emitting macro so cross-refs stay var-refs."
  (:require [lib.code :refer [Operation Subsystem]]
            [canvas.realization.kernel :as kernel]
            [canvas.realization.extraction :as extraction]))

(Subsystem infra-model
  "The model lifecycle — load / get / refresh the held Model from a source path."
  (Operation load-model    "Build (or reload) the held Model from a src path."
    (signature [:=> [:catn [:src extraction/Path]] kernel/StructureDb]))
  (Operation get-model     "The current held Model, or none."
    (signature [:=> [:cat] kernel/StructureDb]))
  (Operation refresh-model "Rebuild the Model from the last src path."
    (signature [:=> [:cat] kernel/StructureDb])))
