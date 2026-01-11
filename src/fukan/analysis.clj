(ns fukan.analysis
  "Static analysis of Clojure source code via clj-kondo.
   Produces raw analysis data (namespaces, vars, usages)."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [fukan.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Schemas
;;
;; We register schemas for documentation/display purposes.
;; Function schemas are defined inline to avoid compile-time resolution issues.

(def ^:private NsDef
  [:map
   [:name :symbol]
   [:filename :string]
   [:doc {:optional true} [:maybe :string]]])

(def ^:private VarDef
  [:map
   [:ns :symbol]
   [:name :symbol]
   [:filename :string]
   [:row :int]
   [:doc {:optional true} [:maybe :string]]
   [:private {:optional true} :boolean]])

(def ^:private VarUsage
  [:map
   [:from :symbol]
   [:from-var {:optional true} :symbol]
   [:to :symbol]
   [:name :symbol]])

(def ^:private NsUsage
  [:map
   [:from :symbol]       ; requiring namespace
   [:to :symbol]         ; required namespace
   [:filename :string]])

(def ^:private AnalysisData
  [:map
   [:namespace-definitions [:vector NsDef]]
   [:var-definitions [:vector VarDef]]
   [:var-usages [:vector VarUsage]]
   [:namespace-usages {:optional true} [:vector NsUsage]]])

;; Register for sidebar display
(schema/register! :fukan.analysis/NsDef NsDef)
(schema/register! :fukan.analysis/VarDef VarDef)
(schema/register! :fukan.analysis/VarUsage VarUsage)
(schema/register! :fukan.analysis/NsUsage NsUsage)
(schema/register! :fukan.analysis/AnalysisData AnalysisData)

(defn run-kondo
  "Runs clj-kondo on src-path and returns the analysis map.

   Returns a map containing:
   - :namespace-definitions - list of {:name :filename ...}
   - :var-definitions - list of {:ns :name :filename :private ...}
   - :var-usages - list of {:from :from-var :to :name ...}
   - :namespace-usages - list of {:from :to :filename ...}

   Throws if clj-kondo fails to run."
  {:malli/schema [:=> [:cat :string] :fukan.analysis/AnalysisData]}
  [src-path]
  (let [config "{:output {:format :edn} :analysis {:var-usages true :var-definitions {:shallow false} :namespace-usages true}}"
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
