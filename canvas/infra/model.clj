(ns canvas.infra.model
  "Canvas port of infra/model.allium + model.boundary.

   Coverage:
     - 3 guarantees          → vocab.behavioral/invariant each
     - fn load_model         → construction/function (FilePath → Model)
     - fn get_model          → vocab.lifecycle/getter (→ Model?)
     - fn refresh_model      → construction/function (→ Model?)
     - fn get_src            → vocab.lifecycle/getter (→ FilePath?)

   Cross-module refs:
     - model.Model           → :model/Model (emits :references relation)

   TODO: no rule lift exists — failure modes (not_loaded, rebuild_failed)
         are structural comments only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "infra.model"

      ;; Guarantees from model.allium

      (invariant "SnapshotIsolation"
        "Consumers read an immutable Model. A rebuild produces a new Model
         value; in-flight requests see the previous snapshot until the next
         dereference."
        (holds-that "snapshot-isolation"))

      (invariant "SingleModelSource"
        "At most one source path is active. Rebuild replaces the entire
         model atomically."
        (holds-that "single-model-source"))

      (invariant "ModelServerDecoupled"
        "Server and model lifecycles are independent. Model refreshes do not
         require server restart. The server calls get_model per-request from
         its side; this module never touches the server."
        (holds-that "model-server-decoupled"))

      ;; Public functions from model.boundary

      (function "load_model"
        "Load and build the model from source. Triggers a full build from the
         given file path and stores the resulting snapshot."
        (takes [src :String])
        (gives :model/Model))

      ;; get_model: () -> Model?  — zero-arg accessor lifted as getter
      (getter "get_model"
        "Return the current model snapshot, or nil if none has been loaded yet."
        :model/Model)

      (function "refresh_model"
        "Rebuild the model from the configured source path. Returns the new
         snapshot, or nil if no source has been loaded."
        (takes [])
        (gives (optional :model/Model)))

      ;; get_src: () -> FilePath?  — zero-arg accessor lifted as getter
      (getter "get_src"
        "Return the configured source path, or nil if none is set."
        :String))))
