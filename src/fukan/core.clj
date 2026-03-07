(ns fukan.core
  "Application entry point and CLI argument parsing.
   Orchestrates the analysis pipeline and starts the web server.

   Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"
  (:require [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

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
     --src PATH    Path to Clojure source directory (required)
     --port PORT   Server port (default: 8080)"
  [& args]
  (let [{:keys [src port]} (parse-args args)]

    ;; Validate required args
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"))
      (System/exit 1))

    (infra-model/load-model src)
    (infra-server/start-server {:port port})
    (println "Press Ctrl+C to stop")))
