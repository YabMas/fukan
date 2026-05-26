(ns fukan.canvas.construction
  "Construction primitives for canvas — function, record, value lifts plus
   exports/closure mechanism. Non-opt-out: every project uses these."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.shape :as shape]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.substrate.store :as store]
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

  (form takes  "Input parameters."        :shape :field+)
  (form gives  "Return value shape."      :shape :type-ref :required true)
  (form effect "An effect this performs." :shape :name-ref :repeatable true)

  (produces [name doc forms]
    (let [takes-vecs  (:takes forms)                ; seq of param vectors
          takes-args  (when takes-vecs (apply concat takes-vecs)) ; flatten all vectors
          gives-arg   (first (:gives forms))        ; e.g. :Account or (optional :Account)
          field-pairs (if takes-args
                        (vec (->> (partition 2 takes-args)
                                  (mapv (fn [[n s]] [n (shape/parse s)]))))
                        [])
          inputs      {:kind :record :fields field-pairs}
          outputs     (shape/parse gives-arg)
          aff         (h/declare-affordance name
                        :shape {:kind :arrow :inputs inputs :outputs outputs}
                        :role :fukan.canvas.monolith/exposed-call)]
      (emit-refs! (:id aff) {:kind :arrow :inputs inputs :outputs outputs})
      (doseq [e-args (:effect forms)]
        (h/declare-relation (:id aff)
                            :fukan.canvas.monolith/performs
                            (first e-args))))))

(defconstructor record
  "An owned data type with named typed fields."

  (form field "A named typed field." :shape :field-pair :repeatable true :required true)

  (produces [name doc forms]
    (let [field-args  (:field forms)
          field-pairs (mapv (fn [args] [(first args) (shape/parse (second args))]) field-args)
          t (sub/type-record name field-pairs)]
      (swap! h/*store* store/transact! t)
      (doseq [[_ field-shape] field-pairs]
        (emit-refs! (:id t) field-shape)))))

(defconstructor value
  "An opaque named type — a named concept whose internal structure is withheld.
   Use for Allium-style value declarations with no exposed fields."

  (produces [name doc forms]
    (let [t (sub/type-primitive name)]
      (swap! h/*store* store/transact! t))))

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
