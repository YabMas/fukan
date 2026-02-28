(ns fukan.cli.gateway
  "Stateful CLI gateway for REPL-based access.
   Wraps the command dispatch layer with persistent session state,
   callable via clj-nrepl-eval for agent workflows."
  (:require [fukan.cli.commands :as commands]
            [fukan.infra.model :as infra-model]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema CommandResponse
  [:map {:description "CLI command response: success status, command name, plus command-specific data."}
   [:ok :boolean]
   [:command :keyword]])

;; -----------------------------------------------------------------------------
;; State

(defonce ^:private session (atom {:view-id nil
                                  :history []
                                  :expanded #{}
                                  :src nil}))

(defn exec
  "Execute a CLI command string. Returns the response EDN map.
   Session state (view-id, history, expanded) persists between calls.
   Model is fetched fresh on each call."
  {:malli/schema [:=> [:cat :string] :CommandResponse]}
  [input]
  (let [model (infra-model/get-model)]
    (when-not model
      (throw (ex-info "No model loaded. Call (go) first." {})))
    (let [state (assoc @session :src (infra-model/get-src))]
      (if-let [parsed (commands/parse-input input)]
        (let [{:keys [response state-update]} (commands/dispatch model state parsed)]
          (when state-update
            (swap! session state-update))
          response)
        {:ok false :error "Empty input."}))))

(defn reset-session
  "Clear session state back to root. Returns :ok."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  (reset! session {:view-id nil
                   :history []
                   :expanded #{}
                   :src nil})
  :ok)
