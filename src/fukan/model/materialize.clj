(ns fukan.model.materialize
  "The materialize / LOWER direction — composable, parameterized by PROJECTION, and
   COMPOSING (a projection can contextualize another). Where extraction LIFTS code into
   the model, materialize PROJECTS the model down into a target representation.

   `render-base` is a MULTIMETHOD dispatching on `[base-projection structure-kind]`: each
   node renders to a fragment of that base's target form. The base dimension is what makes
   the same focus re-present differently per target (an Operation → an impl spec under
   Blueprint, a doc section under Docs).

   A projection may be a BASE (it has its own per-kind renderers, e.g. Blueprint / Docs)
   or a CONTEXTUALIZATION — it renders THROUGH a base it `:contextualizes` and wraps that
   base's output in a framing `:context` (DriftClose = Blueprint framed as drift to close;
   the same composes Blueprint with a 'new feature' or 'refactor' context — no new
   renderers). The base/context relationship lives in the MODEL (the Projection's
   `:contextualizes` + `:context`); this code reads it and applies it. Per Option A, a
   Projection's `:maps` are the intent manifest; these renderers are their realization.

   `materialize-projection` is the model-driven entry; the ad-hoc `materialize-focus`/
   `-module` take a projection + an explicit focus."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.lens :as lens]))

;; ── small query helpers over the substrate ──────────────────────────────────

(defn- rel-target [db eid kind]
  (ffirst (d/q '[:find ?to :in $ ?e ?k
                 :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to]]
               db eid kind)))

(defn- target-names [db eid kind name-attr]
  (->> (d/q '[:find ?to :in $ ?e ?k
              :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to]]
            db eid kind)
       (map (fn [[to]] (name-attr (d/entity db to))))
       sort vec))

(defn- owning-module [db eid]
  ;; membership is :child (generic) or, for a Module, :exposes / :owns
  (ffirst (d/q '[:find ?mn :in $ ?e
                 :where [?r :rel/from ?m] [?r :rel/to ?e] [?r :rel/kind ?k]
                        [(contains? #{:child :exposes :owns} ?k)]
                        [?m :entity/name ?mn]]
               db eid)))

(defn- stage-facts
  "The shaped facts an Operation renderer needs, projection-agnostic: name, doc, owning
   module, labelled params (with their shape eids), output shape eid, effects, calls."
  [db eid]
  (let [e (d/entity db eid)]
    {:nm      (:entity/name e)
     :doc     (:entity/doc e)
     :module  (owning-module db eid)
     :params  (->> (d/q '[:find ?ord ?to ?lbl :in $ ?e
                          :where [?r :rel/from ?e] [?r :rel/kind :in] [?r :rel/to ?to]
                                 [?r :rel/order ?ord] [(get-else $ ?r :rel/label "") ?lbl]]
                        db eid)
                   (sort-by first)
                   (mapv (fn [[_ to lbl]] {:label lbl :shape to})))
     :out     (rel-target db eid :out)
     :effects   (target-names db eid :performs :val/name)
     :delegates (target-names db eid :delegates :entity/name)
     :guidance  (:val/guidance (d/entity db eid))}))

;; ── render-base: each [base projection, kind] renders itself ─────────────────

(defmulti render-base
  "Render node `eid` under BASE projection `base` (e.g. \"Blueprint\" / \"Docs\") to a
   fragment of that base's target form. Dispatches on `[base (:structure/of node)]`.
   Project-owned defmethods supply the per-(base, kind) production and compose referenced
   nodes by calling `render-base` again under the SAME base."
  (fn [db base eid] [base (:structure/of (d/entity db eid))]))

(defmethod render-base :default [db _ eid]
  (:entity/name (d/entity db eid)))

;; shared structural schema rendering (target-agnostic): ref → its named Kind
;; (recurses to the base's :Kind/:default), vector/set/sequential → [child],
;; map → {key: child}; other kinds (scalars/enum) → the kind name. NOTE tuple/or/and
;; fall through to the bare combinator name and LOSE their children here — signature-only,
;; not round-trippable (use the dialect's `render` for a faithful form).
(defn- schema-str [db base eid]
  (let [e (d/entity db eid)]
    (case (:val/kind e)
      "ref"  (render-base db base (rel-target db eid :names))
      ("vector" "set" "sequential") (str "[" (render-base db base (rel-target db eid :of)) "]")
      "map"  (str "{" (str/join ", "
                       (for [feid (map first (d/q '[:find ?f :in $ ?m :where
                                                    [?r :rel/from ?m] [?r :rel/kind :field] [?r :rel/to ?f]]
                                                  db eid))
                             :let [f (d/entity db feid)]]
                         (str (:val/key f) ": " (render-base db base (rel-target db feid :schema))))) "}")
      (str (:val/kind e)))))

;; ── base: Blueprint — the model projected to implementation specs ────────────

(defmethod render-base ["Blueprint" :lib.type.malli/Schema] [db b eid] (schema-str db b eid))

(defmethod render-base ["Blueprint" :lib.code/Operation] [db b eid]
  (let [{:keys [nm doc module params out effects delegates guidance]} (stage-facts db eid)
        sig    (str "(" nm (apply str (map #(str " " (:label %)) params)) ")")
        ptypes (str/join ", " (map #(str (:label %) ": " (render-base db b (:shape %))) params))]
    (str "Implement `" nm "` in module `" module "`.\n"
         (when doc (str "Intent: " doc "\n"))
         "Signature: " sig (when (seq params) (str " where " ptypes))
         " → " (if out (render-base db b out) "Unit") "\n"
         (when (seq effects) (str "Effects: " (str/join ", " effects) "\n"))
         (when (seq delegates) (str "Delegates: " (str/join ", " delegates) "\n"))
         (when guidance (str "Guidance: " guidance "\n"))
         "This is an implementation specification projected from the model — realize it "
         "as a function honoring this signature and intent.")))

;; ── base: Docs — the model projected to reference documentation ──────────────

(defmethod render-base ["Docs" :lib.type.malli/Schema] [db b eid] (schema-str db b eid))

(defmethod render-base ["Docs" :lib.code/Operation] [db b eid]
  (let [{:keys [nm doc module params out effects delegates]} (stage-facts db eid)]
    (str "### " nm "\n"
         (or doc "_No description._") "\n\n"
         "- **Grouping:** " module "\n"
         (when (seq params)
           (str "- **Takes:** "
                (str/join ", " (map #(str (:label %) " (" (render-base db b (:shape %)) ")") params)) "\n"))
         "- **Gives:** " (if out (render-base db b out) "nothing") "\n"
         (when (seq effects) (str "- **Effects:** " (str/join ", " effects) "\n"))
         (when (seq delegates) (str "- **Delegates:** " (str/join ", " delegates) "\n")))))

;; ── contextualization: base + framing context, read from the model ───────────

(defn- proj-node
  "The Projection node named `projection` (nil if none — a bare base name has no node)."
  [db projection]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :structure/of :fukan.canvas.core.lens/Projection] [?e :entity/name ?n]] db projection)))

(defn- base-of
  "The base a `projection` renders through: the name of the Projection it
   `:contextualizes`, or itself when it is a base (or has no model node)."
  [db projection]
  (or (when-let [p (proj-node db projection)]
        (when-let [b (rel-target db p :contextualizes)]
          (:entity/name (d/entity db b))))
      projection))

(defn- context-of
  "The framing `:context` prose a `projection` wraps its base render in, or nil."
  [db projection]
  (when-let [p (proj-node db projection)] (:val/context (d/entity db p))))

(defn render
  "Render `eid` under `projection` — produced under the projection's base (a
   contextualization renders through the base it `:contextualizes`). Per-node; a
   projection's framing `:context` is applied once at the view level (see `compose`)."
  [db projection eid]
  (render-base db (base-of db projection) eid))

;; ── the views: compose a projection's renders over a focus ───────────────────

(defn- renders?
  "Whether base `base` has a SPECIFIC renderer for a node — it is named (anonymous value
   shapes compose inline, never standalone) and `[base kind]` resolves past the :default."
  [db base eid]
  (let [e (d/entity db eid)]
    (and (:entity/name e)
         (not= (get-method render-base [base (:structure/of e)])
               (get-method render-base :default)))))

(defn- compose
  "Render the focus `nodes` `projection` covers (through its base) and join; prepend the
   projection's framing `:context`, if any, once at the top. So a contextualization is the
   base's output wrapped in a context — composed, not duplicated."
  [db projection nodes]
  (let [base (base-of db projection)
        body (->> nodes
                  (filter #(renders? db base %))
                  (sort-by #(:entity/name (d/entity db %)))
                  (map #(render-base db base %))
                  (str/join "\n\n"))
        ctx  (context-of db projection)]
    (if (and ctx (seq body)) (str ctx "\n\n" body) body)))

(defn materialize-view
  "Compose the focus of stored lens `lens-eid` under the Blueprint projection (the
   default implementation-spec target). A single-projection convenience."
  [db lens-eid]
  (compose db "Blueprint" (lens/evaluate-lens db lens-eid)))

(defn ^{:malli/schema [:=> [:cat :StructureDb :ProjectionName [:vector :Eid]] :Instruction]}
  materialize-over
  "Compose `projection` over an explicit `focus` (a node-set) — the focus-consuming
   entry, so a REFINED focus (focus-nodes → refine → …) renders straight into a
   projection. Chaining is plain composition."
  [db projection focus]
  (compose db projection focus))

(defn materialize-finding
  "The probe→projection composition seam: project the UNION of a finding's
   observation foci under `projection`. A probe emits foci; this renders them — so
   `probe → project` is substitution on the shared focus currency, no glue."
  [db projection finding]
  (materialize-over db projection (reduce into #{} (map :focus (:observations finding)))))

(defn materialize-focus
  "Compose `projection` over an ad-hoc focus: the nodes selected by datalog `clauses`
   (binding ?n). The lensless entry — no stored Lens required."
  [db projection clauses]
  (materialize-over db projection (lens/focus-nodes db clauses)))

(defn materialize-module
  "Compose `projection` over the Operations owned by `module-name`. The live by-module entry."
  [db projection module-name]
  (materialize-focus db projection [(list 'Operation '?n) (list 'in-module '?n module-name)]))

(defn materialize-projection
  "The model-driven entry: materialize the modelled `Projection` node `proj-eid` — render
   its `:through` lens focus under its base, wrapped in its `:context` (if a
   contextualization). The Projection's `:maps`/`:context` are the intent manifest; the
   renderers realize them. A prose-only lens (no selection query) yields nil → renders nothing."
  [db proj-eid]
  (let [projection (:entity/name (d/entity db proj-eid))
        lens-eid   (rel-target db proj-eid :through)]
    (compose db projection (lens/evaluate-lens db lens-eid))))
