(ns canvas.materialize.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled with the
   materialize code vocab (`canvas.materialize.vocab`). It exposes the model-lifecycle API
   (load/get/refresh) and owns the `Src` Kind; the model it produces is the kernel's shared
   `StructureDb` (the domain `Model` faculty's data realization), referenced not redeclared.

   Instances are top-level `def`s; the global assembler ingests them (no `build-canvas`)."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]))

(def Src   (Kind))

(def load-model
  (Operation
    (doc "Build (or reload) the held Model from a src path.")
    (in [src Src])
    (out kernel/StructureDb)))
(def get-model
  (Operation
    (doc "The current held Model, or none.")
    (out kernel/StructureDb)))
(def refresh-model
  (Operation
    (doc "Rebuild the Model from the last src path.")
    (out kernel/StructureDb)))

(def infra-model
  (Subsystem "infra.model"
    (exposes load-model get-model refresh-model)   ; the model-lifecycle API
    (owns Src)))                                    ; the src-path Kind it decides
