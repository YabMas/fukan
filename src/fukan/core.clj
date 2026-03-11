(ns fukan.core
  "Application entry point and CLI argument parsing.
   Orchestrates the analysis pipeline and starts the web server.

   Usage: clj -M -m fukan.core --src /path/to/src --analyzers clojure,allium [--port 8080]"
  (:require [clojure.string :as str]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

(defn- parse-analyzers
  "Parse a comma-separated analyzer string into a set of keywords.
   e.g. \"clojure,allium\" -> #{:clojure :allium}"
  [s]
  (->> (str/split s #",")
       (map (comp keyword str/trim))
       set))

(defn- parse-args
  "Parse command line arguments.
   Returns {:src \"path\" :port number :analyzers #{keywords}}."
  [args]
  (loop [args args
         result {:port 8080}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src" (recur remaining (assoc result :src value))
          "--port" (recur remaining (assoc result :port (Integer/parseInt value)))
          "--analyzers" (recur remaining (assoc result :analyzers (parse-analyzers value)))
          ;; Treat non-flag arg as src path
          (recur (clojure.core/rest args) (assoc result :src flag)))))))

(defn -main
  "Main entry point.

   Arguments:
     --src PATH              Path to source directory (required)
     --port PORT             Server port (default: 8080)
     --analyzers KEY,KEY,... Comma-separated analyzer keys (required, e.g. clojure,allium)"
  [& args]
  (let [{:keys [src port analyzers]} (parse-args args)]

    ;; Validate required args
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src --analyzers clojure,allium [--port 8080]"))
      (System/exit 1))

    (when-not analyzers
      (binding [*out* *err*]
        (println "Error: --analyzers argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src --analyzers clojure,allium [--port 8080]"))
      (System/exit 1))

    (infra-model/load-model src analyzers)
    (infra-server/start-server {:port port})
    (println "Press Ctrl+C to stop")))
