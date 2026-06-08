(ns canvas.materialize.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled
   with the fukan-on-fukan grammar (`canvas.vocab`) — its own vocabulary on the
   core, no shared/base structures.

   Instances are top-level `def`s; the global assembler ingests them (no
   `build-canvas`). `infra-model` groups them as the subsystem module."
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
    (child Src)))
