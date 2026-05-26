(ns canvas.web.handler
  "Canvas port of web/handler.allium + handler.boundary.

   Coverage:
     - guarantee PureDelegation   → vocab.behavioral/invariant
     - guarantee PerRequestModel  → vocab.behavioral/invariant
     - surface ViewTransport      → vocab.behavioral/invariant (3 items:
                                    surface-level guarantee + SignalDelivery + FailureModes)
     - fn create_handler          → construction/function (boundary)

   Notes:
     - ViewTransport is a surface declaration, not a record — encoded as
       three invariants: the transport contract, SignalDelivery, and FailureModes.
     - HTTP endpoints (full_navigation, sidebar_update) are boundary-deferred
       per spec comment (Plan 6); only create_handler is in scope here.
     - Cross-module type ref model.Handler uses :model/Handler."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "web.handler"

      ;; ── Guarantees ───────────────────────────────────────────────────────

      (invariant "PureDelegation"
        "Transport never modifies projection or view output. It parses
         request params, calls core functions, and streams the results.
         No domain logic in the transport."
        (holds-that "transport-never-modifies-output"))

      (invariant "PerRequestModel"
        "Each request dereferences the model atom once. All projections
         within a single request see the same snapshot."
        (holds-that "per-request-model-snapshot"))

      ;; ── Surface: ViewTransport ────────────────────────────────────────────

      (invariant "ViewTransport"
        "Endpoint-level contract between the browser and the handler.
         Both endpoints deliver results through the same SSE mechanism:
         patch-elements for HTML (breadcrumb, sidebar) and patch-signals
         for data (graph)."
        (holds-that "sse-delivery-for-all-endpoints"))

      (invariant "SignalDelivery"
        "Graph data is sent via patch-signals, not execute-script. This
         pushes data into a Datastar signal that the GraphViewer component
         observes reactively. Eliminates the failure mode where
         string-interpolated JSON in a <script> tag breaks silently."
        (holds-that "graph-data-via-patch-signals"))

      (invariant "FailureModes"
        "Endpoints surface two failure modes to the client:
         entity_not_found — requested node/edge ID not in model;
         client_disconnected — SSE stream closed by client."
        (holds-that "two-failure-modes-surfaced"))

      ;; ── Boundary Functions ────────────────────────────────────────────────

      (function "create_handler"
        "Create the Ring handler that serves the Fukan application."
        (takes [])
        (gives :model/Handler)))))
