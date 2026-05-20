(ns fukan.agent.system
  "Operating Fukan: status, refresh, help, source. Flat namespace.
   Sandbox surface alongside fukan.agent.api."
  (:require [fukan.infra.model :as infra-model]))

(defn ^{:agent/doc "Snapshot of the daemon and loaded Model."
        :agent/example "(status)"}
  status
  []
  (let [m (infra-model/get-model)]
    {:model-loaded?   (some? m)
     :target          (infra-model/get-src)
     :primitive-count (if m (count (:primitives m)) 0)
     :relation-count  (if m (count (:edges m)) 0)
     :artifact-count  (if m (count (:artifacts m)) 0)
     :violation-count (if m (count (or (:violations m) [])) 0)}))

(defn ^{:agent/doc "Rebuild the loaded Model. Blocks; returns the new status."
        :agent/example "(refresh)"}
  refresh
  []
  (infra-model/refresh-model)
  (status))
