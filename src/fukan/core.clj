(ns fukan.core
  "Application entry point. In Plan 1 the model loader is a stub; analyzers are
   reintroduced in Plans 2/3/5.

   Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"
  (:require [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

(defn- parse-args [args]
  (loop [args args, result {:port 8080}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src"  (recur remaining (assoc result :src value))
          "--port" (recur remaining (assoc result :port (Integer/parseInt value)))
          (recur (rest args) (assoc result :src flag)))))))

(defn -main [& args]
  (let [{:keys [src port]} (parse-args args)]
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"))
      (System/exit 1))
    (infra-model/load-model src)
    (infra-server/start-server {:port port})
    (println "Press Ctrl+C to stop")))
