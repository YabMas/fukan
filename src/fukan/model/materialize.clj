(ns fukan.model.materialize
  "The materialize / LOWER direction — the inverse of extraction. Where extraction
   LIFTS code into the model (code → Operations), materialize PROJECTS the model
   down into implementation specifications (a modelled Stage → what an implementer
   needs to realize it).

   Per fukan's thesis it produces INTENT, not pinned code: a Stage's name, module,
   signature (params from `:in`, return from `:out`), effects (`:performs`) and
   collaborators (`:calls`) — enough for an implementing LLM/human to write the
   function, with the language-specific rendering left to them. So this projection
   is PL-blind; no project-specific renderer is needed (the implementer is the
   renderer). It pairs with `correspondence/unrealized-stages`: that reports WHICH
   modelled Stages lack code; this projects WHAT to implement for each.

   A pure projection of the model (it reads the design db; it is never read BY code)."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

(defn- shape->str
  "Render a Shape value-node to a readable type expression: a `type` leaf → its Kind
   name; a `list` → `[child]`; a `record` → `{label: child, …}`."
  [db eid]
  (let [e (d/entity db eid)]
    (case (:val/kind e)
      "type"   (ffirst (d/q '[:find ?kn :in $ ?s
                              :where [?r :rel/from ?s] [?r :rel/kind :type] [?r :rel/to ?k]
                                     [?k :entity/name ?kn]]
                            db eid))
      "list"   (let [child (ffirst (d/q '[:find ?c :in $ ?s
                                          :where [?r :rel/from ?s] [?r :rel/kind :of] [?r :rel/to ?c]]
                                        db eid))]
                 (str "[" (shape->str db child) "]"))
      "record" (let [fields (d/q '[:find ?lbl ?c :in $ ?s
                                   :where [?r :rel/from ?s] [?r :rel/kind :of] [?r :rel/to ?c]
                                          [?r :rel/label ?lbl]]
                                 db eid)]
                 (str "{" (str/join ", " (map (fn [[l c]] (str l ": " (shape->str db c)))
                                              (sort-by first fields))) "}"))
      (str "<" (:val/kind e) ">"))))

(defn- stage-eid [db stage-name]
  (ffirst (d/q '[:find ?s :in $ ?n
                 :where [?s :structure/of :Stage] [?s :entity/name ?n]]
               db stage-name)))

(defn- in-params [db sid]
  (->> (d/q '[:find ?lbl ?to :in $ ?s
              :where [?r :rel/from ?s] [?r :rel/kind :in] [?r :rel/to ?to] [?r :rel/label ?lbl]]
            db sid)
       (sort-by first)                                ; :in is unordered; label-sort for determinism
       (mapv (fn [[lbl to]] {:label lbl :shape (shape->str db to)}))))

(defn- target-names [db sid kind name-attr]
  (->> (d/q '[:find ?to :in $ ?s ?k
              :where [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/to ?to]]
            db sid kind)
       (map (fn [[to]] (name-attr (d/entity db to))))
       sort vec))

(defn materialize-stage
  "Project the modelled Stage `stage-name` into an implementation specification:
   `{:name :module :params [{:label :shape}] :returns :effects :calls :doc}`.
   Returns nil if no such Stage. NOTE: `:in` is an unordered slot, so multi-param
   signatures are label-sorted, not source-ordered (param-order fidelity would need
   `:in` to be an ordered slot — a model enhancement)."
  [db stage-name]
  (when-let [sid (stage-eid db stage-name)]
    (let [ent     (d/entity db sid)
          module  (ffirst (d/q '[:find ?mn :in $ ?s
                                 :where [?m :module/child ?s] [?m :entity/name ?mn]]
                               db sid))
          out-eid (ffirst (d/q '[:find ?to :in $ ?s
                                 :where [?r :rel/from ?s] [?r :rel/kind :out] [?r :rel/to ?to]]
                               db sid))]
      {:name    stage-name
       :module  module
       :params  (in-params db sid)
       :returns (when out-eid (shape->str db out-eid))
       :effects (target-names db sid :performs :val/name)
       :calls   (target-names db sid :calls :entity/name)
       :doc     (:entity/doc ent)})))

(defn instruction
  "Render an implementation spec as a prose instruction an LLM/human can act on."
  [{:keys [name module params returns effects calls doc]}]
  (let [sig    (str "(" name (apply str (map #(str " " (:label %)) params)) ")")
        ptypes (str/join ", " (map #(str (:label %) ": " (:shape %)) params))]
    (str "Implement `" name "` in module `" module "`.\n"
         (when doc (str "Intent: " doc "\n"))
         "Signature: " sig (when (seq params) (str " where " ptypes)) " → " (or returns "Unit") "\n"
         (when (seq effects) (str "Effects: " (str/join ", " effects) "\n"))
         (when (seq calls) (str "Calls: " (str/join ", " calls) "\n"))
         "This is an implementation specification projected from the model — realize it "
         "as a function honoring this signature and intent.")))
