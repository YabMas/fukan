(ns fukan.canvas.construction
  "Construction primitives for canvas — function, record, value lifts plus
   exports/closure mechanism. Non-opt-out: every project uses these."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.shape :as shape]
            [datascript.core :as d]))

(defn- emit-refs!
  "Walk a parsed shape; for each :ref encountered, transact a :references Relation
   from from-id to the ref's target keyword."
  [from-id shape]
  (case (:kind shape)
    :ref      (h/declare-relation from-id :references (:target shape))
    :optional (emit-refs! from-id (:inner shape))
    :list     (emit-refs! from-id (:elem shape))
    :set      (emit-refs! from-id (:elem shape))
    :sum      (run! #(emit-refs! from-id %) (:variants shape))
    :record   (run! (fn [[_ s]] (emit-refs! from-id s)) (:fields shape))
    :atomic   nil
    :arrow    (do (emit-refs! from-id (:inputs shape))
                  (emit-refs! from-id (:outputs shape)))
    nil))

(defconstructor function
  "A synchronous function call: takes inputs, gives output, may have effects."

  (form takes     "Input parameters."                                    :shape :field+)
  (form gives     "Return value shape."                                  :shape :type-ref :required true)
  (form effect    "An effect this performs."                             :shape :name-ref :repeatable true)
  (form triggers  "Trigger pattern for an associated rule."              :shape :name-ref)
  (form returns   "Returns-label binding for post-condition refs."       :shape :prose)

  (produces [name doc forms]
    (let [takes-vecs     (:takes forms)                ; seq of param vectors
          takes-args     (when takes-vecs (apply concat takes-vecs)) ; flatten all vectors
          gives-arg      (first (:gives forms))        ; e.g. :Account or (optional :Account)
          returns-label  (when-let [r (:returns forms)] (first r))
          field-pairs    (if takes-args
                           (vec (->> (partition 2 takes-args)
                                     (mapv (fn [[n s]] [n (shape/parse s)]))))
                           [])
          inputs         {:kind :record :fields field-pairs}
          outputs        (shape/parse gives-arg)
          aff            (h/declare-affordance name
                           :shape {:kind :arrow :inputs inputs :outputs outputs}
                           :role :fukan.canvas.monolith/exposed-call
                           :doc doc
                           :returns-label (when returns-label (str returns-label)))]
      (emit-refs! (:id aff) {:kind :arrow :inputs inputs :outputs outputs})
      (doseq [e-args (:effect forms)]
        (h/declare-relation (:id aff)
                            :fukan.canvas.monolith/performs
                            (first e-args)))
      (when-let [trigger-args (:triggers forms)]
        (let [rule-name    (str (first trigger-args))
              db           @h/*store*
              module-id    h/*enclosing-module*
              rule-uuid    (when module-id
                             (ffirst (d/q '[:find ?rid
                                            :in $ ?mid ?rn
                                            :where [?m :entity/id ?mid]
                                                   [?m :module/child ?a]
                                                   [?a :entity/name ?rn]
                                                   [?a :entity/id ?rid]]
                                          db module-id rule-name)))]
          (if rule-uuid
            (h/declare-relation (:id aff) :triggers rule-uuid)
            (binding [*out* *err*]
              (println (str "canvas/construction: (triggers " rule-name ") in function \""
                            name "\" — rule not found in enclosing module, skipping Relation"))))))
      aff)))

(defconstructor record
  "An owned data type with named typed fields."

  (form field "A named typed field." :shape :field-pair :repeatable true :required true)

  (produces [name doc forms]
    (let [field-args  (:field forms)
          field-pairs (mapv (fn [args] [(first args) (shape/parse (second args))]) field-args)
          t (h/declare-type name :kind :record :fields field-pairs :doc doc)]
      (doseq [[_ field-shape] field-pairs]
        (emit-refs! (:id t) field-shape)))))

(defconstructor value
  "An opaque named type — a named concept whose internal structure is withheld.
   Use for Allium-style value declarations with no exposed fields."

  (produces [name doc forms]
    (h/declare-type name :kind :atomic :doc doc)))

;; ---------------------------------------------------------------------------
;; Exports / closure mechanism
;;
;; `exports` is a Clojure macro rather than a `defconstructor`-built lift
;; because its body consists of bare positional names rather than
;; form-grammar clauses. This is a deliberate special case; future canvas
;; vocabulary may follow the same pattern when form-grammar doesn't fit
;; the natural syntax.
;; ---------------------------------------------------------------------------

(defn find-entity-by-name [db nm]
  (-> (d/q '[:find ?e
             :in $ ?n
             :where [?e :entity/name ?n]]
           db (str nm))
      ffirst))

(defmacro exports
  "Tag the listed declarations as :exported. Must appear inside `within-module`
   after the named declarations have been transacted.

   Names that don't match any declaration in the current canvas are silently
   ignored — exports is not a typecheck."
  [& names]
  `(let [db# @h/*store*]
     (doseq [n# '~names]
       (when-let [eid# (find-entity-by-name db# n#)]
         (swap! h/*store*
                #(d/db-with % [{:db/id eid# :entity/tag :exported}]))))))
