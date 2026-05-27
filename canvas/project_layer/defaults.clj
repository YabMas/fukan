(ns canvas.project-layer.defaults
  "Canvas port of project_layer/defaults.allium + defaults.boundary.

   Coverage:
     - invariant SelfReferentialIdentity → vocab.behavioral/invariant
     - fn fukan_on_fukan                 → construction/function

   Cross-module refs:
     - registry.Registry → :registry/Registry (emits :references relation)"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "project_layer.defaults"

      ;; Invariant from defaults.allium

      (invariant "SelfReferentialIdentity"
        "fukan_on_fukan returns the identity registry. fukan's source lives at
         namespaces matching its Allium module coords exactly (root_prefix is
         empty), with no custom type overrides and no idioms in Plan 5's MVP
         scope."
        (holds-that "self-referential-identity"))

      ;; Public function from defaults.boundary

      (function "fukan_on_fukan"
        "Return the project registry for fukan analyzing itself. Produces the
         identity registry: empty root_prefix, empty type_overrides, empty idioms."
        (takes [])
        (gives :registry/Registry)))))
