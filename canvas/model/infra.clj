(ns canvas.model.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled
   with the fukan-on-fukan grammar (`canvas.vocab`) — its own vocabulary on the
   core, no shared/base structures.

   Instances are top-level `def`s; the global assembler ingests them (no
   `build-canvas`). `infra-model` groups them as the subsystem module."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]))

(def Src   (Kind))
(def Model (Kind))

(def load-model
  (Stage
    (doc "Build (or reload) the held Model from a src path.")
    (in [src Src])
    (out Model)))
(def get-model
  (Stage
    (doc "The current held Model, or none.")
    (out Model)))
(def refresh-model
  (Stage
    (doc "Rebuild the Model from the last src path.")
    (out Model)))

(def infra-model
  (Module "infra.model" (child Src Model load-model get-model refresh-model)))
