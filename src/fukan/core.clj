(ns fukan.core
  "Application entry point.

   The HTTP/web serving layer (fukan.infra.server + fukan.web.*) is PAUSED
   during the lean-kernel rebuild — parked under .paused/. This entry now does
   a headless model build; the explorer returns once the core stabilises.
   The kernel feedback loop in the meantime is with-canvas + d/q at the REPL.

   Usage: clj -M -m fukan.core --src /path/to/src"
  (:require [fukan.infra.model :as infra-model]))

(defn- parse-args [args]
  (loop [args args, result {}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src"  (recur remaining (assoc result :src value))
          "--port" (recur remaining result) ; accepted but ignored — serving is paused
          (recur (rest args) (assoc result :src flag)))))))

(defn -main [& args]
  (let [{:keys [src]} (parse-args args)]
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src"))
      (System/exit 1))
    (infra-model/load-model src)
    (println "Serving layer is paused (parked under .paused/); model built headlessly.")))
