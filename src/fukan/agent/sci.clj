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
      {:namespaces {'user merged}
       :deny       '[def defn defmacro defprotocol deftype defrecord
                     alter-var-root intern
                     binding var]
       :classes    {}})))

(def ^:private default-timeout-ms 5000)

(defn eval-string
  "Evaluate the given expression string in the agent sandbox.
   Optional opts: {:timeout-ms N} (default 5000).
   Returns {:ok? true :result ... :elapsed-ms N} or
           {:ok? false :error/kind k :error/message m :elapsed-ms N}."
  ([s] (eval-string s {}))
  ([s {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (let [ctx       (make-ctx)
         start     (System/currentTimeMillis)
         fut       (future
                     (try {:ok? true :result (sci/eval-string* ctx s)}
                          (catch Throwable e
                            {:ok? false
                             :error/kind (or (some-> e ex-data :type) :runtime)
                             :error/message (.getMessage e)})))
         result    (deref fut timeout-ms ::timeout)
         elapsed   (- (System/currentTimeMillis) start)]
     (cond
       (= result ::timeout)
       (do (future-cancel fut)
           {:ok? false
            :error/kind :timeout
            :error/elapsed-ms elapsed
            :error/message (str "eval exceeded " timeout-ms "ms")})

       :else
       (assoc result :elapsed-ms elapsed)))))
