(ns fukan.agent.sci
  "SCI sandbox for agent eval. Locks the evaluator to the fukan.agent.api and
   fukan.agent.system surfaces. No Java interop, no IO, no thread spawning.

   A single shared SCI context persists across calls so that view defs loaded
   via the views-loader are reachable from subsequent eval-string calls."
  (:require [sci.core :as sci]
            [fukan.agent.api]
            [fukan.agent.system]))

(defn- ns-bindings [ns-sym]
  (into {} (map (fn [[sym v]] [sym @v]) (ns-publics ns-sym))))

(def ^:private view-ctx (atom nil))

(defn- build-ctx
  "Build the shared SCI context. Same sandbox restrictions, but `def`/`defn`
   are permitted because the views loader needs them. Other dangerous symbols
   remain denied."
  []
  (let [api-bindings    (ns-bindings 'fukan.agent.api)
        system-bindings (ns-bindings 'fukan.agent.system)
        merged          (merge api-bindings system-bindings)]
    (sci/init
      {:namespaces {'user merged}
       :deny       '[alter-var-root intern binding var]
       :classes    {}})))

(defn- ensure-view-ctx! []
  (or @view-ctx (reset! view-ctx (build-ctx))))

(defn reset-ctx! []
  (reset! view-ctx nil))

(def ^:private default-timeout-ms 5000)

(defn eval-string
  "Evaluate the given expression string in the agent sandbox.
   Optional opts: {:timeout-ms N} (default 5000).
   Uses the shared view-ctx so agent-defined views are reachable."
  ([s] (eval-string s {}))
  ([s {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (let [ctx     (ensure-view-ctx!)
         start   (System/currentTimeMillis)
         fut     (future
                   (try {:ok? true :result (sci/eval-string* ctx s)}
                        (catch Throwable e
                          {:ok? false
                           :error/kind (or (some-> e ex-data :type) :runtime)
                           :error/message (.getMessage e)})))
         result  (deref fut timeout-ms ::timeout)
         elapsed (- (System/currentTimeMillis) start)]
     (cond
       (= result ::timeout)
       (do (future-cancel fut)
           {:ok? false
            :error/kind :timeout
            :error/elapsed-ms elapsed
            :error/message (str "eval exceeded " timeout-ms "ms")})

       :else
       (assoc result :elapsed-ms elapsed)))))

(defn ^:export eval-string-as-view
  "Evaluate a form-string in the view-loading context.
   Defs land in the shared ctx and are reachable from subsequent eval-string calls.
   Resolved dynamically by views_loader via `requiring-resolve`; the ^:export
   metadata flags this for clojure-lsp's unused-public-var exemption."
  [s]
  (try
    (let [ctx (ensure-view-ctx!)]
      {:ok? true :result (sci/eval-string* ctx s)})
    (catch Exception e
      {:ok? false
       :error/kind (or (some-> e ex-data :type) :runtime)
       :error/message (.getMessage e)})))
