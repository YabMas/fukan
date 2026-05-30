(ns fukan.canvas.core.check
  (:require [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.defquery :as dq]
            [fukan.canvas.identity :as identity]))

(def ^:dynamic *constraints* nil)

(defmacro with-constraint-registry [& body]
  `(binding [*constraints* (atom [])]
     ~@body))

(defmacro register-constraint!
  "Register a constraint. Captures source location (:ns, :line) at the call site.

   `name` — an unquoted symbol used as the constraint identifier.
   `message` — a string describing the violation.
   `body` — a quoted Clojure form (e.g. `'[(Module ?m)]`) — the defquery clause body."
  [cname message body]
  (let [loc {:ns (str *ns*) :line (:line (meta &form))}]
    `(when *constraints*
       (swap! *constraints* conj
              {:name ~cname :message ~message :body ~body :source-location ~loc}))))

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
  "Builds a Datascript query from expanded clauses, finding all logic vars.
   Declares `% ` so the classification-stratum rules (used by primitive-kind
   expansions like (Module ?m)) are available."
  [expanded]
  (let [vars (logic-vars expanded)]
    (vec (concat [:find] vars [:in '$ '%] [:where] expanded))))

(defn- enrich-offenders
  "Given a db and a seq of raw offender tuples (each a vector of eids / values),
   return a seq of maps {:eid <first-eid> :stable-id <string-or-nil>}.
   Takes the first element of each result tuple as the canonical eid."
  [db results]
  (mapv (fn [row]
          (let [eid     (first row)
                sid     (when (integer? eid)
                          (identity/stable-id-for-eid db eid))]
            {:eid eid :stable-id sid}))
        results))

(defn check-all
  "Run all registered constraints over the current store, return violations.

   Each violation map has the shape:
     {:constraint    <symbol>
      :message       <string>
      :source-location {:ns <string> :line <int-or-nil>}
      :offenders     [{:eid <eid> :stable-id <string-or-nil>} ...]}"
  []
  (let [db (some-> h/*store* deref)]
    (if (or (nil? db) (nil? *constraints*))
      []
      (vec
        (for [{:keys [name message body source-location]} @*constraints*
              :let [expanded (dq/expand body)
                    query    (build-query expanded)
                    results  (d/q query db classification/rules)]
              :when (seq results)]
          {:constraint      name
           :message         message
           :source-location source-location
           :offenders       (enrich-offenders db results)})))))
