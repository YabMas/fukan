(ns canvas.architecture.orchestration.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled with the
   materialize code vocab. It exposes the model-lifecycle API (load/get/refresh); the model it
   produces is the kernel's shared `StructureDb` (the domain `Model`'s data realization)
   and the source path is the shared `extraction/Path` — both referenced, not redeclared.

   Authored as one nested `Module` form: the operations live inside it (no separate `def`s),
   each interned as a var by the def-emitting macro so cross-refs stay var-refs."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.orchestration.pipeline :as pipeline]
            [canvas.architecture.ingestion.extraction :as extraction]
            [canvas.architecture.cozo.db :as cozo-db]
            [canvas.architecture.cozo.mirror :as cozo-mirror]))

(Module infra-model
  "The model lifecycle — load / get / refresh the held Model from a source path. During the
   datascript→Cozo cut-over it holds the model dually (the ds db + a Cozo mirror, `get-cozo`)."
  (Operation load-model    "Build (or reload) the held Model from a src path, holding it dually (ds + a Cozo mirror)."
    {:signature  [:=> [:catn [:src extraction/Path]] substrate/StructureDb]
     :performs   [:io :require :state :throws]
     :delegates  [pipeline/build-model cozo-mirror/mirror cozo-db/close]})  ; build the model + mirror it to Cozo
  (Operation get-model     "The current held Model, or none."
    {:signature [:=> [:cat] substrate/StructureDb]})
  (Operation get-cozo      "The current held Model's Cozo mirror (a Cozo db handle), or none."
    {:signature [:=> [:cat] :any]})
  (Operation refresh-model "Rebuild the Model from the last src path."
    {:signature [:=> [:cat] substrate/StructureDb]
     :performs  [:io :require :state :throws]})
  (Operation get-src "The current source path the held Model was built from, or none."
    {:signature [:=> [:cat] extraction/Path]}))
