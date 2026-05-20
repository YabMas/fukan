(ns fukan.agent.sci
  "SCI sandbox for agent eval. Locks the evaluator to the fukan.agent.api and
   fukan.agent.system surfaces. No Java interop, no IO, no thread spawning."
  (:require [sci.core :as sci]
            [fukan.agent.api]
            [fukan.agent.system]))

(defn- ns-bindings [ns-sym]
  (into {} (map (fn [[sym v]] [sym @v]) (ns-publics ns-sym))))

(defn- make-ctx []
  (let [api-bindings    (ns-bindings 'fukan.agent.api)
        system-bindings (ns-bindings 'fukan.agent.system)
        merged          (merge api-bindings system-bindings)]
    (sci/init
      {:namespaces {'user merged}})))

(defn eval-string
  "Evaluate the given expression string in the agent sandbox.
   Returns either {:ok? true :result …} or {:ok? false :error/kind … :error/message …}."
  [s]
  (try
    (let [ctx    (make-ctx)
          result (sci/eval-string* ctx s)]
      {:ok? true :result result})
    (catch Exception e
      (let [data (ex-data e)]
        {:ok? false
         :error/kind (or (:type data) :runtime)
         :error/message (.getMessage e)}))))
