(ns fukan.agent.views-loader
  "Load .fukan/agent-views.clj into the SCI agent surface.

   Reads forms from the file, evaluates each in the shared SCI view-ctx
   (via fukan.agent.sci/eval-string-as-view), and captures per-form errors
   into a load report. The agent sees successful defs as if they were
   built-in L2 views."
  (:refer-clojure :exclude [reset!])
  (:require [fukan.agent.sci :as agent-sci]))

(defonce ^:private load-report (atom {:loaded [] :errors []}))

(defn reset! []
  (clojure.core/reset! load-report {:loaded [] :errors []})
  (agent-sci/reset-ctx!))

(defn last-report [] @load-report)

(defn load-file! [path]
  (reset!)
  (let [parsed (try
                 {:ok (read-string (str "[" (slurp path) "]"))}
                 (catch Exception e
                   {:err {:error/kind :syntax
                          :error/message (.getMessage e)
                          :error/path path}}))]
    (if (:err parsed)
      (do (swap! load-report update :errors conj (:err parsed))
          @load-report)
      (do
        (doseq [form (:ok parsed)]
          (let [r (agent-sci/eval-string-as-view (pr-str form))]
            (if (:ok? r)
              (when-let [sym (and (seq? form) (= 'defn (first form)) (second form))]
                (swap! load-report update :loaded conj sym))
              (swap! load-report update :errors conj
                     (merge r {:error/form form})))))
        @load-report))))
