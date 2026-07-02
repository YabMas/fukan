(ns canvas.architecture.orchestration.infra
  "Canvas spec for fukan's infra.model subsystem (the model lifecycle), modelled with the
   materialize code vocab. It exposes the model-lifecycle API (load/get/refresh); the model it
   produces is the kernel's shared `StructureDb` (the domain `Model`'s data realization)
   and the source path is the shared `extraction/Path` — both referenced, not redeclared.

   Authored as one nested `Module` form: the operations live inside it (no separate `def`s),
   each interned as a var by the def-emitting macro so cross-refs stay var-refs."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.principles.parse-dont-validate :refer [TrustBoundary]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.orchestration.pipeline :as pipeline]
            [canvas.architecture.ingestion.extraction :as extraction]
            [canvas.architecture.cozo.db :as cozo-db]
            [canvas.architecture.cozo.query :as cozo-query]))

(Module infra-model
  "The model lifecycle — load / get / refresh the held Model from a source path. The held Model is
   the native Cozo build; `get-model` returns it."
  (Operation load-model    "Build (or reload) the held Model from a src path — the native Cozo build, closing the prior db."
    {:signature  [:=> [:catn [:src extraction/Path]] substrate/StructureDb]
     :performs   [:io :stderr :require :state :throws]
     :delegates  [pipeline/build-model cozo-query/q cozo-db/close]})  ; build the cozo model; cq/q reads the count; close the prior
  (Operation get-model     "The current held Model (the Cozo substrate), or none."
    {:signature [:=> [:cat] substrate/StructureDb]})
  (Operation refresh-model "Rebuild the Model from the last src path."
    {:signature [:=> [:cat] substrate/StructureDb]
     :performs  [:io :stderr :require :state :throws]})
  (Operation get-src "The current source path the held Model was built from, or none."
    {:signature [:=> [:cat] extraction/Path]}))

;; StructureDb is fukan's parse-don't-validate TRUST BOUNDARY. The designation lives HERE — where
;; the boundary is STAFFED — because the parse points are this module's lifecycle ops (substrate,
;; which owns the Kind, cannot reference back without a require cycle). `load-model`/`refresh-model`
;; ESTABLISH the trust (raw source → held Model); `get-model` merely hands the held artifact along
;; (a reader, deliberately NOT declared). Readers over the Model must be total (Totality).
(TrustBoundary infra-trust-boundary {:kind      substrate/StructureDb
                                     :parsed-by [load-model refresh-model]})
