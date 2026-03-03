(ns fukan.cli.explorer
  "CLI explorer session loop.
   Reads commands from stdin, prints EDN responses to stdout.
   Diagnostic output goes to stderr only."
  (:require [fukan.cli.commands :as commands]
            [fukan.projection.api :as proj]))

(defn- init-state
  "Create initial session state."
  [model src-path]
  {:model model
   :view-id nil
   :history []
   :expanded #{}
   :show-private #{}
   :src src-path})

(defn- print-response
  "Print a single EDN response to stdout (one line, never pretty-printed)."
  [response]
  (println (pr-str response))
  (flush))

(defn- print-init
  "Print the :init response with model summary."
  [model src-path]
  (let [root (proj/find-root model)
        stats (proj/overview model)]
    (print-response {:ok true
                     :command :init
                     :src src-path
                     :root-id (:id root)
                     :root-label (:label root)
                     :total-nodes (:total-nodes stats)
                     :total-edges (:total-edges stats)})))

(defn start-session
  "Start the CLI explorer session.
   Reads commands from stdin, writes EDN to stdout.
   All diagnostic output goes to stderr."
  {:malli/schema [:=> [:cat :Model :string] :nil]}
  [model src-path]
  (let [state (atom (init-state model src-path))]
    (print-init model src-path)
    (loop []
      (when-let [line (read-line)]
        (if-let [parsed (commands/parse-input line)]
          (if (= "quit" (:command parsed))
            (print-response {:ok true :command :quit})
            (let [{:keys [response state-update]}
                  (commands/dispatch model @state parsed)]
              (when state-update
                (swap! state state-update))
              (print-response response)
              (recur)))
          ;; Blank line — ignore and keep reading
          (recur))))))
