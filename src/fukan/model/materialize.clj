(ns fukan.model.materialize
  "The materialize / LOWER direction — composable. Where extraction LIFTS code into
   the model, materialize PROJECTS the model down into implementation specifications.

   `render` is a MULTIMETHOD dispatching on a node's structure kind: each primitive
   renders ITSELF to an implementation-instruction fragment (text — intent, not pinned
   code). The defmulti is the open extension point; a project adds a defmethod per
   primitive kind it models (fukan-on-itself's renders for :Stage / :Shape / :Kind are
   here — the instruction itself stays out of the pure vocab,
   [[feedback_pure_domains_separate_correspondence]] in spirit). Composition along
   references falls out of dispatch: a :Stage's render calls `render` on its Shapes,
   which dispatches to :Shape, which recurses on its children.

   `materialize-view` composes the renders over a lens's focus sub-graph
   (`evaluate-lens`) — the implementation specs for the primitives in the focus area.
   It pairs with `correspondence/unrealized-stages`: that says WHICH Stages lack code,
   this says WHAT to implement. A pure projection of the model."
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

;; ── render: each primitive renders itself; owners compose via `render` ───────

(defmulti render
  "Render node `eid` (an instance of its kind) to an implementation-instruction
   fragment. Dispatches on `:structure/of`. Project-owned defmethods supply the
   per-kind rendering and compose referenced primitives by calling `render` again."
  (fn [db eid] (:structure/of (d/entity db eid))))

(defmethod render :default [db eid]
  (:entity/name (d/entity db eid)))

(defmethod render :Kind [db eid]
  (:entity/name (d/entity db eid)))

(defmethod render :Shape [db eid]
  (let [e (d/entity db eid)]
    (case (:val/kind e)
      "type"   (render db (rel-target db eid :type))                  ; → :Kind
      "list"   (str "[" (render db (rel-target db eid :of)) "]")      ; → :Shape (child)
      "record" (str "{" (str/join ", " (map (fn [[lbl to]] (str lbl ": " (render db to)))
                                             (sort-by first (labelled-targets db eid :of)))) "}")
      (str "<" (:val/kind e) ">"))))

(defmethod render :Stage [db eid]
  (let [e       (d/entity db eid)
        nm      (:entity/name e)
        module  (owning-module db eid)
        params  (->> (labelled-targets db eid :in) (sort-by first)
                     (mapv (fn [[lbl to]] {:label lbl :shape to})))
        out-eid (rel-target db eid :out)
        effects (target-names db eid :performs :val/name)
        calls   (target-names db eid :calls :entity/name)
        sig     (str "(" nm (apply str (map #(str " " (:label %)) params)) ")")
        ptypes  (str/join ", " (map #(str (:label %) ": " (render db (:shape %))) params))]
    (str "Implement `" nm "` in module `" module "`.\n"
         (when (:entity/doc e) (str "Intent: " (:entity/doc e) "\n"))
         "Signature: " sig (when (seq params) (str " where " ptypes))
         " → " (if out-eid (render db out-eid) "Unit") "\n"
         (when (seq effects) (str "Effects: " (str/join ", " effects) "\n"))
         (when (seq calls) (str "Calls: " (str/join ", " calls) "\n"))
         "This is an implementation specification projected from the model — realize it "
         "as a function honoring this signature and intent.")))

;; ── the view: compose the renders over a lens's focus ────────────────────────

(defn- compose
  "Render every node in `nodes` (a set of eids) and join — the shared composition step.
   Value shapes are absorbed inline by their owners; named references (e.g. a Stage's
   :calls) print as names."
  [db nodes]
  (->> nodes
       (sort-by #(:entity/name (d/entity db %)))
       (map #(render db %))
       (str/join "\n\n")))

(defn materialize-view
  "Materialize the focus of stored lens `lens-eid` into a composed implementation spec.
   The lens bounds which primitives appear."
  [db lens-eid]
  (compose db (lens/evaluate-lens db lens-eid)))

(defn materialize-focus
  "Materialize an ad-hoc focus: compose the render of every node selected by datalog
   `:where` `clauses` (binding `?n`). The lensless entry — no stored Lens required."
  [db clauses]
  (compose db (lens/focus-nodes db clauses)))

(defn materialize-module
  "Materialize the implementation spec for module `module-name`: compose the projected
   render of every Stage that module owns. The live by-module entry point."
  [db module-name]
  (materialize-focus db [(list 'Stage '?n) (list 'in-module '?n module-name)]))
