(ns fukan.model.typing
  "The type-dialect plug-point: a slot for a project's TYPE dialect.

   Fukan ships only this mechanic — a place to register ONE dialect (malli for
   fukan-on-itself). The registry is language-NEUTRAL: a dialect produces types
   (malli's happen to be schemas). A dialect is a map of bridge fns at the edges:
     :render   (fn [db eid] → code-form)            type subgraph → code
     :parse    (fn [form]   → entity-maps)          code-form → datoms (later)
     :adheres? (fn [db eid extracted] → bool)       model type ↔ code (later)
   The core never interprets a type; everything between the bridges is plain
   queryable structure.")

(defonce ^:private dialect (atom nil))

(defn register-type-dialect!
  "Register the project's type dialect (a bridge-fn map). Replaces any prior."
  [d]
  (reset! dialect d)
  nil)

(defn render-type
  "Render the type at `eid` in `db` to a code-form via the dialect, or nil."
  [db eid]
  (when-let [d @dialect] (when-let [f (:render d)] (f db eid))))

(defn parse-type
  "Parse a code-form type into entity-maps via the dialect, or nil."
  [form]
  (when-let [d @dialect] (when-let [f (:parse d)] (f form))))

(defn type-adheres?
  "Check the modelled type at `eid` against an extracted shape, or nil."
  [db eid extracted]
  (when-let [d @dialect] (when-let [f (:adheres? d)] (f db eid extracted))))
