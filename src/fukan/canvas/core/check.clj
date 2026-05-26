(ns fukan.canvas.core.check
  (:require [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.defquery :as dq]))

(def ^:dynamic *constraints* nil)

(defmacro with-constraint-registry [& body]
  `(binding [*constraints* (atom [])]
     ~@body))

(defn register-constraint! [cname message body]
  (when *constraints*
    (swap! *constraints* conj
           {:name cname :message message :body body})))

(defn- logic-vars
  "Collect all logic-variable symbols (those starting with ?) from a list of
   datom-pattern clauses."
  [clauses]
  (->> clauses
       (mapcat identity)
       (filter #(and (symbol? %) (.startsWith (str %) "?")))
       distinct
       vec))

(defn- build-query
  "Builds a Datascript query map from expanded clauses, finding all logic vars."
  [expanded]
  (let [vars (logic-vars expanded)]
    (vec (concat [:find] vars [:where] expanded))))

(defn check-all
  "Run all registered constraints over the current store, return violations."
  []
  (let [db (some-> h/*store* deref)]
    (if (or (nil? db) (nil? *constraints*))
      []
      (vec
        (for [{:keys [name message body]} @*constraints*
              :let [expanded (dq/expand body)
                    query    (build-query expanded)
                    results  (d/q query db)]
              :when (seq results)]
          {:constraint name
           :message message
           :offenders (vec results)})))))
