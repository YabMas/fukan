(ns fukan.canvas.construction
  "Construction primitives for canvas — function, record, value lifts plus
   exports/closure mechanism. Non-opt-out: every project uses these."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.shape :as shape]
            [fukan.canvas.vocab.construct :as construct]
            [fukan.canvas.vocab.registry :as registry]
            [datascript.core :as d]))

(def tag-definitions
  "The base (non-opt-out) vocabulary: the container kind, the export marker, and
   the function/record/value lifts below."
  [{:tag :canvas/module :family :Module :payload :none
    :doc "A grouping/namespace host; owns its children via containment."}
   {:tag :canvas/exported :family nil :payload :none
    :doc "Marker: tags a declaration as part of its module's exported API."}
   {:tag :canvas/value :family :Type :payload :none
    :doc "An opaque named type — a concept whose internal structure is withheld."}
   {:tag :canvas/record :family :Type :payload :record
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A data type with named, typed fields."}
   {:tag :canvas/function :family :Affordance :payload :arrow
    :edges [{:strategy :shape-refs  :edge :references}
            {:strategy :to-keywords :from :effect   :edge :canvas/performs}
            {:strategy :by-name     :from :triggers :edge :triggers}
            {:strategy :by-name     :from :emits    :edge :emits :role :canvas/event}]
    :doc "A synchronous function call: takes inputs, gives an output, may have effects."}])

(registry/register! tag-definitions)

(defconstructor function
  "A synchronous function call: takes inputs, gives output, may have effects."

  (form takes     "Input parameters."                                    :shape :field+)
  (form gives     "Return value shape."                                  :shape :type-ref :required true)
  (form effect    "An effect this performs."                             :shape :name-ref :repeatable true)
  (form triggers  "Trigger pattern for an associated rule."              :shape :name-ref)
  (form emits     "An event this function emits."                        :shape :name-ref :repeatable true)
  (form returns   "Returns-label binding for post-condition refs."       :shape :prose)

  (produces [name doc forms]
    (let [takes-vecs    (:takes forms)
          takes-args    (when takes-vecs (apply concat takes-vecs))
          gives-arg     (first (:gives forms))
          returns-label (when-let [r (:returns forms)] (first r))
          field-pairs   (if takes-args
                          (vec (->> (partition 2 takes-args)
                                    (mapv (fn [[n s]] [n (shape/parse s)]))))
                          [])
          arrow         {:kind :arrow
                         :inputs {:kind :record :fields field-pairs}
                         :outputs (shape/parse gives-arg)}]
      (construct/build :canvas/function name arrow
                       {:effect   (:effect forms)
                        :triggers (when (:triggers forms) [(:triggers forms)])
                        :emits    (:emits forms)}
                       :doc doc
                       :returns-label (when returns-label (str returns-label))))))

(defconstructor record
  "An owned data type with named typed fields."

  (form field "A named typed field." :shape :field-pair :repeatable true :required true)

  (produces [name doc forms]
    (let [field-pairs (mapv (fn [args] [(first args) (shape/parse (second args))]) (:field forms))]
      (construct/build :canvas/record name {:kind :record :fields field-pairs} {} :doc doc))))

(defconstructor value
  "An opaque named type — a named concept whose internal structure is withheld.
   Use for Allium-style value declarations with no exposed fields."

  (produces [name doc forms]
    (construct/build :canvas/value name nil {} :doc doc)))

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
  "Tag the listed declarations as :canvas/exported. Must appear inside `within-module`
   after the named declarations have been transacted.

   Names that don't match any declaration in the current canvas are silently
   ignored — exports is not a typecheck."
  [& names]
  `(let [db# @h/*store*]
     (doseq [n# '~names]
       (when-let [eid# (find-entity-by-name db# n#)]
         (swap! h/*store*
                #(d/db-with % [{:db/id eid# :entity/tag :canvas/exported}]))))))
