(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [fukan.agent.edb :as edb]
            [fukan.agent.query :as query]
            [fukan.infra.model :as infra-model]))

;; -- Helpers ------------------------------------------------------------------

(defn- ensure-model
  "Return the currently loaded Model, or throw a `:model-not-loaded` ex-info."
  []
  (or (infra-model/get-model)
      (throw (ex-info "no model loaded" {:type :model-not-loaded}))))

;; -- L0 Kernel ----------------------------------------------------------------

(defn ^{:agent/layer :L0
        :agent/doc "Datalog over the loaded Model. Form: [:find … :where …]."
        :agent/example "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])"}
  q
  "Evaluate a Datalog query against the loaded Model.
   Returns a vector of binding maps keyed by :find variables."
  [form]
  (let [m (ensure-model)]
    (query/evaluate (query/parse form) (edb/model->edb m))))
