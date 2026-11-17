(ns fukan.core
  "Application entry point.

   Builds the model headlessly and exits. It serves nothing: the browser explorer is a
   cockpit command (`fukan.repl/explore`), because it reads the model a REPL holds.

   Usage: clj -M -m fukan.core --src /path/to/src"
  (:require [fukan.infra.model :as infra-model]))

(defn- parse-args [args]
  (loop [args args, result {}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src"  (recur remaining (assoc result :src value))
          "--port" (recur remaining result) ; accepted and ignored — this entry serves nothing
          (recur (rest args) (assoc result :src flag)))))))

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] :nil]}
  -main
  [& args]
  (let [{:keys [src]} (parse-args args)]
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src"))
      (System/exit 1))
    (infra-model/load-model src)
    (println "Model built headlessly. To explore it in a browser, use (explore) at the REPL.")))
