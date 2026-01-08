(ns fukan.analysis
  "Static analysis of Clojure source code via clj-kondo.
   Produces raw analysis data (namespaces, vars, usages)."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]))

(defn run-kondo
  "Runs clj-kondo on src-path and returns the analysis map.
   
   Returns a map containing:
   - :namespace-definitions - list of {:name :filename ...}
   - :var-definitions - list of {:ns :name :filename :private ...}
   - :var-usages - list of {:from :from-var :to :name ...}
   
   Throws if clj-kondo fails to run."
  [src-path]
  (let [config "{:output {:format :edn} :analysis {:var-usages true :var-definitions {:shallow false}}}"
        result (shell/sh "clj-kondo"
                         "--lint" src-path
                         "--config" config
                         "--parallel")]
    (if (and (zero? (:exit result))
             (empty? (:err result)))
      (-> result :out edn/read-string :analysis)
      ;; clj-kondo returns non-zero for lint errors, but we still get analysis
      ;; Only fail if we can't parse the output
      (let [parsed (try
                     (-> result :out edn/read-string)
                     (catch Exception e
                       (throw (ex-info "Failed to parse clj-kondo output"
                                       {:exit (:exit result)
                                        :stderr (:err result)
                                        :stdout (:out result)}
                                       e))))]
        (:analysis parsed)))))
