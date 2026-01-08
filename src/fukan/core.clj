(ns fukan.core
  "Application entry point and CLI argument parsing.
   Orchestrates the analysis pipeline and starts the web server.
   
   Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"
  (:require [fukan.analysis :as analysis]
            [fukan.model :as model]
            [fukan.web.handler :as handler]
            [org.httpkit.server :as http]))

(defn- parse-args
  "Parse command line arguments.
   Returns {:src \"path\" :port number}."
  [args]
  (loop [args args
         result {:port 8080}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src" (recur remaining (assoc result :src value))
          "--port" (recur remaining (assoc result :port (Integer/parseInt value)))
          ;; Treat non-flag arg as src path
          (recur (clojure.core/rest args) (assoc result :src flag)))))))

(defn -main
  "Main entry point.
   
   Arguments:
     --src PATH   Path to Clojure source directory (required)
     --port PORT  Server port (default: 8080)"
  [& args]
  (let [{:keys [src port]} (parse-args args)]

    ;; Validate required args
    (when-not src
      (println "Error: --src argument is required")
      (println "Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]")
      (System/exit 1))

    (println (str "Analyzing " src "..."))

    ;; Run analysis
    (let [analysis (analysis/run-kondo src)
          _ (println (str "Found " (count (:namespace-definitions analysis)) " namespaces, "
                          (count (:var-definitions analysis)) " var definitions"))

          ;; Build model
          model (model/build-model analysis)
          _ (println (str "Built " (count (:nodes model)) " nodes, "
                          (count (:edges model)) " edges"))

          ;; Create handler
          handler (handler/create-handler model)]

      ;; Start server
      (http/run-server handler {:port port})
      (println (str "\nFukan running at http://localhost:" port))
      (println "Press Ctrl+C to stop"))))
