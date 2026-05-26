(ns fukan.canvas.pilot.server
  "Canvas port of infra/server.allium + server.boundary.

   Coverage:
     - value ServerOpts  → (record ...)
     - value ServerInfo  → (record ...)
     - fn start_server   → (function ...)
     - fn stop_server    → (function ...)
     - fn get_port       → (function ...)

   Gaps (see doc/plans/2026-05-25-pilot-port-findings.md):
     - guarantee SingleServerInstance — no invariant/guarantee lift
     - failure mode annotations       — no lift equivalent
     - exports: ServerOpts ServerInfo — no exports/closure mechanism
     - ServerOpts.port: Integer?      — optional field shape not expressible;
                                        approximated as :Integer"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "infra.server"
      ;; Value types from server.allium
      ;;
      ;; ServerOpts.port is Integer? (optional) — the record lift only accepts
      ;; keyword type refs, so optionality is dropped here. Gap 3.
      (record "ServerOpts"
        "HTTP server configuration."
        (field port :Integer))

      (record "ServerInfo"
        "Running server information."
        (field port :Integer))

      ;; Public functions from server.boundary
      ;;
      ;; start_server returns ServerInfo? (optional) — gives only accepts a
      ;; single type-ref keyword, optionality of the return type is dropped.
      (function "start_server"
        "Start the HTTP server on a given port."
        (takes [opts :ServerOpts])
        (gives :ServerInfo))

      ;; stop_server returns Unit — no :Unit type exists, so we use :Unit
      ;; as an opaque keyword. No takes (zero-arg function): the function
      ;; lift requires a takes clause for shape construction; we pass an
      ;; empty vector to signal no inputs.
      (function "stop_server"
        "Stop the running HTTP server."
        (takes [])
        (gives :Unit))

      (function "get_port"
        "Return the port the server is listening on."
        (takes [])
        (gives :Integer)))))
