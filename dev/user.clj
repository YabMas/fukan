(ns user
  "Development helpers for REPL-driven workflow.
   Uses Integrant for system lifecycle and clj-reload for code reloading."
  (:require [clj-reload.core :as reload]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server]
            [fukan.web.handler]
            [fukan.cli.gateway :as gateway]
            [clojure.java.io :as io]))

;; Initialize clj-reload - tracks src and dev directories
;; The 'user namespace is excluded from reloading
(reload/init
  {:dirs ["src" "dev"]
   :no-reload '#{user}})

(defn- read-config []
  (ig/read-string (slurp (io/resource "fukan/system.edn"))))

(ig-repl/set-prep!
  (fn [] (-> (read-config)
             (assoc-in [:fukan.infra/model :src] "src"))))

(defn- wire-gateway! []
  (when-let [model-state (:fukan.infra/model state/system)]
    (gateway/init! model-state)))

(defn go
  "Start the Integrant system."
  []
  (ig-repl/go)
  (wire-gateway!))

(defn halt
  "Stop the Integrant system."
  []
  (ig-repl/halt))

(defn reset
  "Halt the system, force-reload all code, and restart.
   Use after editing any source files."
  []
  (halt)
  (reload/reload {:only :loaded})
  (go))

(defn refresh-model
  "Rebuild model from source without restarting the server.
   Use after editing the codebase being analyzed."
  []
  (if-let [model-state (:fukan.infra/model state/system)]
    (do
      (infra-model/refresh! model-state)
      (println "Refreshed. Browser will see changes on next request."))
    (println "System not running. Use (go) first.")))

(defn status
  "Print current development environment status."
  []
  (if state/system
    (let [model-state (:fukan.infra/model state/system)
          {:keys [port]} (:fukan.infra/server state/system)]
      (println "Server: running on port" port)
      (if-let [m (:model @model-state)]
        (println "Model:" (count (:nodes m)) "nodes,"
                 (count (:edges m)) "edges"
                 "(src:" (:src @model-state) ")")
        (println "Model: not loaded")))
    (println "System: not running")))

(comment
  ;; Quick start for this project
  (go)
  (halt)
  (reset)

  ;; Rebuild model without restarting server
  (refresh-model)

  ;; Check what's running
  (status))
