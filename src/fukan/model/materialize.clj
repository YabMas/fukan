(ns fukan.model.materialize
  "The materialize / LOWER direction — composable, and now parameterized by PROJECTION.
   Where extraction LIFTS code into the model, materialize PROJECTS the model down into
   a target representation.

   `render` is a MULTIMETHOD dispatching on `[projection structure-kind]`: each node
   renders to a fragment of THIS projection's target form. The second dimension —
   the projection (a target name like \"Blueprint\" or \"Docs\") — is what makes the
   same focus re-present differently per target (a Stage → an impl spec under Blueprint,
   a doc section under Docs). Composition along references falls out of dispatch: a
   renderer calls `render` again with the SAME projection.

   A modelled `Projection` (canvas.vocab.projection: `:through` a Lens + `:maps` a set
   of `Mapping`s) is the intent-level MANIFEST — it declares, as data, which source kinds
   this projection re-presents into what (\"a function → a defn\"). These defmethods are
   that manifest's realization; the prose Mappings document intent, they don't key the
   dispatch (the renderers do). A project adds a projection by supplying its renderers
   plus a Projection model. `materialize-projection` is the model-driven entry; the
   ad-hoc `materialize-focus`/`-module` take a projection + an explicit focus."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.lens :as lens]))

;; ── small query helpers over the substrate ──────────────────────────────────

(defn- rel-target [db eid kind]
  (ffirst (d/q '[:find ?to :in $ ?e ?k
                 :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to]]
               db eid kind)))

(defn- labelled-targets [db eid kind]
  (d/q '[:find ?lbl ?to :in $ ?e ?k
         :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to] [?r :rel/label ?lbl]]
       db eid kind))

(defn- target-names [db eid kind name-attr]
  (->> (d/q '[:find ?to :in $ ?e ?k
              :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to]]
            db eid kind)
       (map (fn [[to]] (name-attr (d/entity db to))))
       sort vec))

(defn- owning-module [db eid]
  (ffirst (d/q '[:find ?mn :in $ ?e :where [?m :module/child ?e] [?m :entity/name ?mn]] db eid)))

(defn- stage-facts
  "The shaped facts a Stage renderer needs, projection-agnostic: name, doc, owning
   module, labelled params (with their shape eids), output shape eid, effects, calls."
  [db eid]
  (let [e (d/entity db eid)]
    {:nm      (:entity/name e)
     :doc     (:entity/doc e)
     :module  (owning-module db eid)
     :params  (->> (labelled-targets db eid :in) (sort-by first)
                   (mapv (fn [[lbl to]] {:label lbl :shape to})))
     :out     (rel-target db eid :out)
     :effects (target-names db eid :performs :val/name)
     :calls   (target-names db eid :calls :entity/name)}))

;; ── render: each [projection, kind] renders itself; owners compose via `render` ──

(defmulti render
  "Render node `eid` under `projection` (a target name, e.g. \"Blueprint\" / \"Docs\")
   to a fragment of that projection's target form. Dispatches on
   `[projection (:structure/of node)]`. Project-owned defmethods supply the
   per-(projection, kind) production and compose referenced nodes by calling `render`
   again with the SAME projection."
  (fn [db projection eid] [projection (:structure/of (d/entity db eid))]))

(defmethod render :default [db _ eid]
  (:entity/name (d/entity db eid)))

;; shared structural shape rendering (target-agnostic): type leaf → its Kind (recurses
;; to the projection's :Kind/:default), list → [child], record → {label: child}.
(defn- shape-str [db projection eid]
  (let [e (d/entity db eid)]
    (case (:val/kind e)
      "type"   (render db projection (rel-target db eid :type))
      "list"   (str "[" (render db projection (rel-target db eid :of)) "]")
      "record" (str "{" (str/join ", " (map (fn [[lbl to]] (str lbl ": " (render db projection to)))
                                             (sort-by first (labelled-targets db eid :of)))) "}")
      (str "<" (:val/kind e) ">"))))

;; ── projection: Blueprint — the model projected to implementation specs ──────

(defmethod render ["Blueprint" :Shape] [db p eid] (shape-str db p eid))

(defmethod render ["Blueprint" :Stage] [db p eid]
  (let [{:keys [nm doc module params out effects calls]} (stage-facts db eid)
        sig    (str "(" nm (apply str (map #(str " " (:label %)) params)) ")")
        ptypes (str/join ", " (map #(str (:label %) ": " (render db p (:shape %))) params))]
    (str "Implement `" nm "` in module `" module "`.\n"
         (when doc (str "Intent: " doc "\n"))
         "Signature: " sig (when (seq params) (str " where " ptypes))
         " → " (if out (render db p out) "Unit") "\n"
         (when (seq effects) (str "Effects: " (str/join ", " effects) "\n"))
         (when (seq calls) (str "Calls: " (str/join ", " calls) "\n"))
         "This is an implementation specification projected from the model — realize it "
         "as a function honoring this signature and intent.")))

;; ── projection: Docs — the model projected to reference documentation ────────

(defmethod render ["Docs" :Shape] [db p eid] (shape-str db p eid))

(defmethod render ["Docs" :Stage] [db p eid]
  (let [{:keys [nm doc module params out effects calls]} (stage-facts db eid)]
    (str "### " nm "\n"
         (or doc "_No description._") "\n\n"
         "- **Module:** " module "\n"
         (when (seq params)
           (str "- **Takes:** "
                (str/join ", " (map #(str (:label %) " (" (render db p (:shape %)) ")") params)) "\n"))
         "- **Gives:** " (if out (render db p out) "nothing") "\n"
         (when (seq effects) (str "- **Effects:** " (str/join ", " effects) "\n"))
         (when (seq calls) (str "- **Calls:** " (str/join ", " calls) "\n")))))

;; ── the views: compose a projection's renders over a focus ───────────────────

(defn- compose
  "Render every node in `nodes` (a set of eids) under `projection` and join."
  [db projection nodes]
  (->> nodes
       (sort-by #(:entity/name (d/entity db %)))
       (map #(render db projection %))
       (str/join "\n\n")))

(defn materialize-view
  "Compose the focus of stored lens `lens-eid` under the Blueprint projection (the
   default implementation-spec target). A single-projection convenience."
  [db lens-eid]
  (compose db "Blueprint" (lens/evaluate-lens db lens-eid)))

(defn materialize-focus
  "Compose `projection` over an ad-hoc focus: the nodes selected by datalog `clauses`
   (binding ?n). The lensless entry — no stored Lens required."
  [db projection clauses]
  (compose db projection (lens/focus-nodes db clauses)))

(defn materialize-module
  "Compose `projection` over the Stages owned by `module-name`. The live by-module entry."
  [db projection module-name]
  (materialize-focus db projection [(list 'Stage '?n) (list 'in-module '?n module-name)]))

(defn materialize-projection
  "The model-driven entry: materialize the modelled `Projection` node `proj-eid` —
   render its `:through` lens focus under the projection's own name (which selects the
   per-kind renderers). The Projection's `:maps` are the intent manifest; these renderers
   realize them. Throws if the projection's lens carries no selection query."
  [db proj-eid]
  (let [projection (:entity/name (d/entity db proj-eid))
        lens-eid   (rel-target db proj-eid :through)]
    (compose db projection (lens/evaluate-lens db lens-eid))))
