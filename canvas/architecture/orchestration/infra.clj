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
            [canvas.architecture.ingestion.source :as source]
            [canvas.architecture.cozo.db :as cozo-db]
            [canvas.architecture.cozo.build :as cozo-build]
            [canvas.architecture.cozo.mirror :as cozo-mirror]))

(Module infra-model
  "The model lifecycle — load / get / refresh the held Model from a source path. During the
   datascript→Cozo cut-over it holds the model dually: the ds db (`get-model`, oracle) + the NATIVE
   Cozo build (`get-cozo`, what consumers read)."
  (Operation load-model    "Build (or reload) the held Model from a src path: the ds build (oracle) + the native Cozo build (model->cozo) consumers read."
    {:signature  [:=> [:catn [:src extraction/Path]] substrate/StructureDb]
     :performs   [:io :require :state :throws]
     ;; build the ds oracle + the native Cozo model; canvas-namespaces/extract-roots feed model->cozo
     :delegates  [pipeline/build-model source/canvas-namespaces cozo-build/model->cozo cozo-db/close]})
  (Operation get-model     "The current held ds Model (the oracle), or none."
    {:signature [:=> [:cat] substrate/StructureDb]})
  (Operation get-cozo      "The current held Model's Cozo db handle (the native build), or none."
    {:signature [:=> [:cat] :any]})
  (Operation refresh-model "Rebuild the Model from the last src path."
    {:signature [:=> [:cat] substrate/StructureDb]
     :performs  [:io :require :state :throws]})
  (Operation set-model-for-test! "Test helper — directly hold an arbitrary ds Model + its Cozo MIRROR (tests pass a ds db, so this mirrors rather than native-builds)."
    {:signature [:=> [:catn [:m substrate/StructureDb]] :any]
     :performs  [:state]                            ; resets the held-state atom
     :delegates [cozo-mirror/mirror]})
  (Operation get-src "The current source path the held Model was built from, or none."
    {:signature [:=> [:cat] extraction/Path]}))
