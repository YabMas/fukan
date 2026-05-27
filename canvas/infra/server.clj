(ns canvas.infra.server
  "Canvas port of infra/server.allium + server.boundary."
  (:refer-clojure :exclude [alias])
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.identity :refer [alias]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "infra.server"

      (record "ServerOpts"
        "HTTP server configuration."
        (field port (optional :Integer)))

      (record "ServerInfo"
        "Running server information."
        (field port :Integer))

      (invariant "SingleServerInstance"
        "At most one HTTP server runs at a time."
        (holds-that "at-most-one server is active"))

      (function "start_server"
        "Start the HTTP server on the given options."
        (takes [opts :ServerOpts])
        (gives (optional :ServerInfo)))

      (function "stop_server"
        "Stop the running HTTP server."
        (takes [])
        (gives :Unit))

      (getter "get_port"
        "Return the port the server is listening on."
        :Integer)

      ;; Historical alias: the function was once referenced as "start" before
      ;; being renamed to "start_server". Declared here so old persisted ids
      ;; (bookmarks, external references) continue to resolve.
      (alias "infra.server/start" "start_server")

      (exports ServerOpts ServerInfo))))
