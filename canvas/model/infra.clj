(ns canvas.model.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled
   with the fukan-on-fukan grammar (`canvas.vocab`) — its own vocabulary on the
   core, no shared/base structures."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "infra.model"
      (Kind "Src")
      (Kind "Model")
      (Stage "load-model"
        (doc "Build (or reload) the held Model from a src path.")
        (in [src Src])
        (out Model))
      (Stage "get-model"
        (doc "The current held Model, or none.")
        (out Model))
      (Stage "refresh-model"
        (doc "Rebuild the Model from the last src path.")
        (out Model)))))
