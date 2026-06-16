(ns fukan.canvas.projection.overview
  "Project a navigable SYSTEM OVERVIEW from the model — the canvas's front door.

   The flat file list under `canvas/` can't show fukan's shape; the shape lives in the model — the
   SUBJECT stratum (`canvas.subject`): one hub `Model`, one `Source` (two polarities — design intent ↓, code reality ↑), a `Lens`
   that reads a Graph (→ a sub-Graph) and a `Projection` that works on a Graph (→ a ProjectionTarget) — each
   authored as a PORTRAIT (grammar, no instances) and tagged with the code Module that realizes it
   (`canvas.manifest/Manifest`, the faculty build-manifest). Rendered live, so it can never drift
   from the spec it describes. Read this instead of `ls canvas/`.

   Pure projection: model db → string."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

(def ^:private SUBJ "canvas.subject")

(defn- realizers
  "{faculty-name [realizing-module-name…]} from the faculty build-manifest — joined to the reflected
   faculty grammar node by tag (the faculties are portraits, not instances)."
  [db]
  (reduce (fn [m [cn mn]] (update m cn (fnil conj []) mn))
          {}
          (d/q '[:find ?cn ?mn
                 :where [?r :structure/of :canvas.manifest/Manifest]
                        [?r :val/realizes ?tag]
                        [?c :structure/of :lib.grammar/Structure] [?c :val/tag ?tag] [?c :entity/name ?cn]
                        [?b :rel/from ?r] [?b :rel/kind :by] [?b :rel/to ?mod] [?mod :entity/name ?mn]] db)))

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
        snode    (fn [nm] (ffirst (d/q '[:find ?n :in $ ?tag
                                         :where [?s :structure/of :lib.grammar/Structure] [?s :val/tag ?tag]
                                                [?s :entity/name ?n]] db (str ":" SUBJ "/" nm))))
        model-n  (snode "Model")
        mslot    (fn [label] (struct-slot-target db (str ":" SUBJ "/Model") label))
        gslot    (fn [label] (struct-slot-target db (str ":" SUBJ "/Graph") label))
        made     (gslot "made-of")        ; the node kind (now on Graph)
        wired    (gslot "wired-by")       ; the relation kind (now on Graph)
        authored (mslot "authored-in")    ; the vocabulary (on Model)
        source-n (snode "Source")
        lens-n   (snode "Lens")
        proj-n   (snode "Projection")
        ;; canvas.subject holds ALL portraits (substrate/hub/sources/acts too), so the
        ;; namespace predicate isn't enough — this set selects just the grammar-layer subset
        grammar  (sort (filter #{"Form" "Structure" "Slot" "Law" "Vocabulary"}
                               (map first
                                    (d/q '[:find ?n
                                           :where [?s :structure/of :lib.grammar/Structure]
                                                  [?s :val/tag ?t] [?s :entity/name ?n]
                                                  [(clojure.string/starts-with? ?t ":canvas.subject/")]] db))))]
    (str/join
     "\n"
     (remove
      nil?
      [ "FUKAN — projected system overview"
        (str "  " nstruct " structures · " nrel " relations · derived live from the model")
        ""
        "━━ THE SUBJECT — what fukan is (pure grammar; portraits, no instances) ━━"
        ""
        (str "  ◆ " model-n " — the hub: structured as a Graph (" made "s wired by " wired "s), authored in a " authored (by model-n))
        (str "      ⌞ defstructure (the Form) builds a " authored " over " made " + " wired " — bottom-up language building")
        ""
        "  GRAMMAR — the language you build with (a shape IS a Structure)"
        (str "    ⚙ " (str/join " · " grammar) " — Form produces a Structure (Slots + Laws); a Vocabulary is the set")
        ""
        "  IN — two origins converge on the Model (↓ design intent, ↑ code reality)"
        (str "    ⇊⇈ " source-n " — the in-fold, two polarities" (by source-n))
        ""
        "  OUT — reads and renders (Graph transforms)"
        (str "    ◎ " lens-n " (lens) — reads a Graph → a (sub-)Graph: a focus, Graph → Graph" (by lens-n))
        (str "    ▶ " proj-n " (projection) — works on a Graph (optionally through a lens) → a target" (by proj-n))]))))
