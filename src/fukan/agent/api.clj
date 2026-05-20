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

;; -- L1 Probes ----------------------------------------------------------------

(def ^:private default-limit 1000)

(defn- summary [p]
  (select-keys p [:id :kind :label]))

(defn- apply-filters [p filters]
  (reduce-kv
    (fn [keep? k v]
      (and keep?
           (case k
             :kind  (= v (:kind p))
             :label (= v (:label p))
             (throw (ex-info (str "unknown primitive filter: " k)
                             {:type :unknown-filter :filter k})))))
    true filters))

(defn- envelope [rows limit]
  (let [total (count rows)]
    {:rows (vec (take limit rows))
     :truncated? (> total limit)
     :total total}))

(defn ^{:agent/layer :L1
        :agent/doc "List Model primitive summaries. Optional filters: :kind :label.
                    Returns {:rows … :truncated? bool :total N}."
        :agent/example "(primitives :kind :primitive/behaviour)"}
  primitives
  [& {:keys [limit] :or {limit default-limit} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (vals (:primitives m))
                     (filter #(apply-filters % filters))
                     (map summary))]
    (envelope rows limit)))
