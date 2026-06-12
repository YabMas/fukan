(ns fukan.canvas.projection.overview
  "Project a navigable SYSTEM OVERVIEW from the model — the canvas's front door.

   The flat file list under `canvas/` can't show fukan's shape; the shape lives in the model — the
   SUBJECT stratum (`canvas.subject`): one hub `Model`, two `Source`s (origins), a `Lens`
   that reads it and a `Projection` that synthesises from it (the two uses, not twins), and the
   `Correspondence` that closes the loop — each tagged with the code Module that realizes it
   (`SubjectRealization`, the verify-down seam). Rendered live, so it can never drift from the spec
   it describes. Read this instead of `ls canvas/`.

   Pure projection: model db → string."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

(def ^:private SUBJ "canvas.subject")

(defn- realizers
  "{concept-name [realizing-module-name…]} from the SubjectRealization seam."
  [db]
  (reduce (fn [m [cn mn]] (update m cn (fnil conj []) mn))
          {}
          (d/q '[:find ?cn ?mn
                 :where [?r :structure/of :canvas.correspondence/SubjectRealization]
                        [?a :rel/from ?r] [?a :rel/kind :realizes] [?a :rel/to ?c] [?c :entity/name ?cn]
                        [?b :rel/from ?r] [?b :rel/kind :by] [?b :rel/to ?mod] [?mod :entity/name ?mn]] db)))

(defn- rel-target-name
  "The entity-name the node `?e` points to via relation `kind` (or nil)."
  [db e kind]
  (ffirst (d/q '[:find ?n :in $ ?e ?k
                 :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?t] [?t :entity/name ?n]] db e kind)))

(defn- struct-slot-target
  "The name of the structure a `Structure`'s slot points to — read from the REFLECTED grammar
   (`lib.grammar`), so type-level facts (e.g. the Model's foundation) render without instances."
  [db struct-tag slot-label]
  (ffirst (d/q '[:find ?tn :in $ ?tag ?label
                 :where [?s :structure/of :lib.grammar/Structure] [?s :val/tag ?tag]
                        [?r :rel/from ?s] [?r :rel/label ?label] [?r :rel/to ?t] [?t :entity/name ?tn]]
               db struct-tag slot-label)))

(defn system-overview
  "Render the subject model as a navigable system map (string)."
  [db]
  (let [rz       (realizers db)
        by       (fn [n] (when-let [ms (seq (sort (rz n)))] (str "   ⟶ " (str/join ", " ms))))
        nstruct  (count (d/q '[:find ?e :where [?e :structure/of _]] db))
        nrel     (count (d/q '[:find ?r :where [?r :rel/kind _]] db))
        model-e  (ffirst (d/q '[:find ?e :in $ ?t :where [?e :structure/of ?t]] db (keyword SUBJ "Model")))
        model-n  (when model-e (:entity/name (d/entity db model-e)))
        ;; the foundation is TYPE-level — read it from the Model structure's slots (reflected
        ;; grammar), not from instances (there are none; the singletons were pure shadows)
        mslot    (fn [label] (struct-slot-target db (str ":" SUBJ "/Model") label))
        made     (mslot "made-of")        ; the node kind
        wired    (mslot "wired-by")       ; the relation kind
        authored (mslot "authored-in")    ; the vocabulary
        sources  (sort (d/q '[:find ?n ?p :in $ ?t
                              :where [?s :structure/of ?t] [?s :entity/name ?n] [?s :val/polarity ?p]] db (keyword SUBJ "Source")))
        lens     (->> (d/q '[:find ?l ?n :in $ ?t
                             :where [?l :structure/of ?t] [?l :entity/name ?n]] db (keyword SUBJ "Lens"))
                      (sort-by second))
        projs    (->> (d/q '[:find ?p ?n :in $ ?t
                             :where [?p :structure/of ?t] [?p :entity/name ?n]] db (keyword SUBJ "Projection"))
                      (sort-by second))
        corr     (ffirst (d/q '[:find ?n :in $ ?t :where [?c :structure/of ?t] [?c :entity/name ?n]] db (keyword SUBJ "Correspondence")))
        arrow    {"design-down" "↓ design" "code-up" "↑ code"}]
    (str/join
     "\n"
     (remove
      nil?
      (concat
       ["FUKAN — projected system overview"
        (str "  " nstruct " structures · " nrel " relations · derived live from the model")
        ""
        "━━ THE SUBJECT — what fukan is ━━"
        ""
        (str "  ◆ " model-n " — the hub: a graph of " made "s wired by " wired "s, authored in a " authored (by model-n))
        (str "      ⌞ defstructure (the Form) builds a " authored " over " made " + " wired " — bottom-up language building (grammar, not instances)")
        ""
        "  IN — two origins converge on the Model"]
       (for [[n p] sources]
         (str "    " (arrow p p) "  " n (by n)))
       [""
        "  OUT — the Model is used two ways (not twins)"]
       (for [[_ n] lens]
         (str "    ◎ " n " (lens) — reads the Model: a focus → a sub-graph to reason with" (by n)))
       (for [[e n] projs]
         (str "    ▶ " n " (projection) — re-presents the Model, through " (rel-target-name db e :through) (by n)))
       [""
        (str "  ⊣ " corr " — extract ⊣ project, the loop closing" (by corr))])))))
