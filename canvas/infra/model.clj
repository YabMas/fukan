(ns canvas.infra.model
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle) — the
   first spec authored in the lean-kernel defstructure vocabulary. Returns a
   structure substrate db (the model is the db, design decision (ii))."
  (:require [fukan.canvas.core.structure :as s]
            [fukan.canvas.structures :refer [Type Function]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "infra.model"
      (Type "Src")
      (Type "Model")
      (Function "load-model"
        (doc "Build (or reload) the held Model from a src path.")
        (takes [src Src])
        (gives Model))
      (Function "get-model"
        (doc "The current held Model, or none.")
        (gives Model))
      (Function "refresh-model"
        (doc "Rebuild the Model from the last src path.")
        (gives Model)))))
