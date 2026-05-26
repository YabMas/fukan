(ns canvas.infra.server
  "Canvas port of infra/server.allium + server.boundary.

   Phase 2 migration: escape hatches replaced by vocab lifts.

   Coverage:
     - value ServerOpts  → construction/record with :doc annotation
     - value ServerInfo  → construction/record with :doc annotation
     - guarantee SingleServerInstance → vocab.behavioral/invariant
     - fn start_server   → construction/function
     - fn stop_server    → construction/function (zero-arg; :Unit return)
     - fn get_port       → vocab.lifecycle/getter

   Notes:
     - ServerOpts.port is Integer? (optional) — field lift accepts only
       simple keyword type refs; optionality carried as prose comment.
     - start_server return type ServerInfo? (optional) — gives takes a
       single keyword; optionality expressed in docstring.
     - exports: ServerOpts ServerInfo — using construction/exports macro."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "infra.server"

      ;; Value types from server.allium
      ;; ServerOpts.port: Integer? — optionality not expressible in field lift;
      ;; carried in docstring. The record lift handles :doc annotation natively
      ;; after Task 1.
      (record "ServerOpts"
        "HTTP server configuration. Field port is optional (Integer?)."
        (field port :Integer))

      (record "ServerInfo"
        "Running server information."
        (field port :Integer))

      ;; Guarantee from server.allium
      (invariant "SingleServerInstance"
        "At most one HTTP server runs at a time."
        (holds-that "at-most-one server is active"))

      ;; Public functions from server.boundary
      ;; start_server returns ServerInfo? (optional) — optional return not
      ;; expressible as a keyword in gives; noted in docstring.
      (function "start_server"
        "Start the HTTP server on a given port. Returns ServerInfo? (optional)."
        (takes [opts :ServerOpts])
        (gives :ServerInfo))

      ;; stop_server: no args, returns Unit.
      (function "stop_server"
        "Stop the running HTTP server."
        (takes [])
        (gives :Unit))

      ;; get_port: zero-arg Optional<Integer> accessor — lifecycle/getter.
      (getter "get_port"
        "Return the port the server is listening on, or nil if not running."
        :Integer)

      ;; Exports closure from server.boundary
      (exports ServerOpts ServerInfo))))
