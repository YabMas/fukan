(ns fukan.model.schema
  "The schema-dialect plug-point: a slot for a project's type/schema language.

   Fukan ships only this mechanic — a place to register ONE dialect (malli for
   fukan-on-itself). A dialect is a map of bridge fns at the edges:
     :render   (fn [db eid] → code-form)            schema subgraph → code
     :parse    (fn [form]   → entity-maps)          code-form → datoms (later)
     :adheres? (fn [db eid extracted] → bool)       model schema ↔ code (later)
   The core never interprets a schema; everything between the bridges is plain
   queryable structure.")

(defonce ^:private dialect (atom nil))

(defn register-schema-dialect!
  "Register the project's schema dialect (a bridge-fn map). Replaces any prior."
  [d]
  (reset! dialect d)
  nil)

(defn render-schema
  "Render the schema at `eid` in `db` to a code-form via the dialect, or nil."
  [db eid]
  (when-let [d @dialect] (when-let [f (:render d)] (f db eid))))

(defn parse-schema
  "Parse a code-form schema into entity-maps via the dialect, or nil."
  [form]
  (when-let [d @dialect] (when-let [f (:parse d)] (f form))))

(defn schema-adheres?
  "Check the modelled schema at `eid` against an extracted shape, or nil."
  [db eid extracted]
  (when-let [d @dialect] (when-let [f (:adheres? d)] (f db eid extracted))))
