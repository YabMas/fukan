(ns fukan.core
  "Application entry point and CLI argument parsing.
   Orchestrates the analysis pipeline and starts the web server or CLI explorer.

   Usage: clj -M -m fukan.core --src /path/to/src [--port 8080] [--mode web|cli]"
  (:require [fukan.infra.model :as infra-model]
            [fukan.cli.explorer :as cli]
            [clojure.java.io :as io]
            [integrant.core :as ig]))

(defn- parse-args
  "Parse command line arguments.
   Returns {:src \"path\" :port number :mode :web|:cli}."
  [args]
  (loop [args args
         result {:port 8080 :mode :web}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src" (recur remaining (assoc result :src value))
          "--port" (recur remaining (assoc result :port (Integer/parseInt value)))
          "--mode" (recur remaining (assoc result :mode (keyword value)))
          ;; Treat non-flag arg as src path
          (recur (clojure.core/rest args) (assoc result :src flag)))))))

(defn- read-config
  "Read the Integrant system configuration from resources."
  []
  (ig/read-string (slurp (io/resource "fukan/system.edn"))))

(defn -main
  "Main entry point.

   Arguments:
     --src PATH    Path to Clojure source directory (required)
     --port PORT   Server port (default: 8080)
     --mode MODE   web (default) or cli"
  [& args]
  (let [{:keys [src port mode]} (parse-args args)]

    ;; Validate required args
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src [--port 8080] [--mode web|cli]"))
      (System/exit 1))

    (case mode
      :cli
      (let [;; Build model directly for CLI — no Integrant needed
            model-state (atom {:model nil :src nil})
            model (binding [*out* *err*]
                    (infra-model/load-model! model-state src))]
        (cli/start-session model src))

      ;; Default: web mode
      (let [config (-> (read-config)
                       (assoc-in [:fukan.infra/model :src] src)
                       (assoc-in [:fukan.infra/server :port] port))]
        (ig/load-namespaces config)
        (ig/init config)
        (println "Press Ctrl+C to stop")))))
