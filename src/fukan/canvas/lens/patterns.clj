(ns fukan.canvas.lens.patterns
  "Patterns lens — structural lens; surfaces clusters of structurally-similar
   Affordances as rule-of-three lift candidates.

   For each Affordance, compute a signature:

     {:role            <:affordance/role>
      :input-types     (sort <input type keywords>)
      :output-types    (sort <output type keywords>)
      :has-formal-expr <boolean>
      :has-returns     <boolean>}

   Group by signature; keep groups of size >= 3. For each cluster, attach
   an :existing-lift annotation if all members share a role that already
   maps to a vocab lift (validation/checker, lifecycle/getter, etc.) — in
   which case the cluster is framing-for-confirmation; otherwise it is a
   rule-of-three lift candidate the LLM should weigh.

   Full design in git history:
   doc/plans/2026-05-26-feedback-signals-design.md § 2. Pattern recurrence — WEIGH tier."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.identity :as identity]))

;; ---------------------------------------------------------------------------
;; Role → existing-lift mapping
;; ---------------------------------------------------------------------------

(def ^:private role->existing-lift
  "Map a canvas role to the vocab lift that produces it, when one exists.
   When every member of a cluster shares a role in this map, the cluster
   is already lifted — the lens frames it as a consistency confirmation
   rather than a new-lift candidate."
  {:canvas/checker   "vocab.validation/checker"
   :canvas/getter    "vocab.lifecycle/getter"
   :canvas/invariant "vocab.behavioral/invariant"
   :canvas/rule      "vocab.behavioral/rule"
   :canvas/event     "vocab.event/event"
   :canvas/handler   "vocab.event/handler"})

;; ---------------------------------------------------------------------------
;; Compute
;; ---------------------------------------------------------------------------

(defn- affordance-signature
  "Build the structural signature map for an Affordance entity (by eid)."
  [db eid]
  (let [ent (d/entity db eid)]
    {:role            (classification/direct-kind db eid)
     :input-types     (vec (sort (or (:affordance/input-types ent) [])))
     :output-types    (vec (sort (or (:affordance/output-types ent) [])))
     :has-formal-expr (boolean (:affordance/formal-expression ent))
     :has-returns     (boolean (:affordance/returns-label ent))}))

(defn- affordance-eids
  [db]
  (->> (d/datoms db :aevt :entity/type)
       (keep (fn [d] (when (= :Affordance (.-v d)) (.-e d))))))

(defn- cluster-for-signature
  "Build the cluster map for a group of affordance eids sharing a signature."
  [db signature eids]
  (let [members (->> eids
                     (map #(identity/stable-id-for-eid db %))
                     (filter some?)
                     sort
                     vec)
        existing-lift (get role->existing-lift (:role signature))]
    (cond-> {:signature signature
             :size      (count eids)
             :members   members}
      existing-lift (assoc :existing-lift existing-lift))))

(defn compute
  "Cluster Affordances by structural signature. Return clusters of size >= 3,
   ordered by descending size then by signature for stable output.

   The `opts` map is currently unused; reserved for future configurability
   (e.g. minimum cluster size, role filters)."
  [canvas-db _opts]
  (let [eids       (affordance-eids canvas-db)
        sig->eids  (group-by #(affordance-signature canvas-db %) eids)
        clusters   (->> sig->eids
                        (filter (fn [[_sig es]] (>= (count es) 3)))
                        (map (fn [[sig es]] (cluster-for-signature canvas-db sig es)))
                        (sort-by (juxt #(- (:size %))
                                       #(pr-str (:signature %))))
                        vec)]
    {:clusters clusters}))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn- format-type-list
  [ts]
  (if (seq ts)
    (str "[" (str/join " " (map pr-str ts)) "]")
    "[]"))

(defn- render-signature
  [{:keys [role input-types output-types has-formal-expr has-returns]}]
  (str "- role: " (pr-str role) "\n"
       "- inputs: "  (format-type-list input-types)  "\n"
       "- outputs: " (format-type-list output-types) "\n"
       "- formal-expression?: " has-formal-expr "\n"
       "- returns-label?: "     has-returns))

(defn- render-cluster
  [idx {:keys [signature size members existing-lift]}]
  (let [header (str "### Cluster " (inc idx) " — " size " affordances")
        sig    (render-signature signature)
        mems   (str/join "\n" (map #(str "  - " %) members))
        verdict (if existing-lift
                  (str "**Already lifted by `" existing-lift "`.** "
                       "Weigh: do all members read consistently for the lift, or "
                       "is one an outlier that drifted in by accident?")
                  (str "**No existing lift covers this shape.** "
                       "Rule of three triggered — weigh whether the recurrence "
                       "is an undiscovered abstraction or intentional repetition "
                       "(sister-module symmetry, intra-module pattern). If lift "
                       "would improve readability, propose vocabulary."))]
    (str header "\n\n"
         sig "\n\n"
         "Members:\n" mems "\n\n"
         verdict)))

(defn render
  "Render compute output as markdown. `findings` is the map returned by
   `compute` (or nil if compute was skipped); `opts` is currently unused."
  [findings _opts]
  (let [clusters (:clusters findings)]
    (if (empty? clusters)
      "No clusters of structurally-similar affordances (size >= 3) found."
      (str "Found " (count clusters) " structural cluster(s) of size >= 3.\n\n"
           (->> clusters
                (map-indexed render-cluster)
                (str/join "\n\n"))))))

;; ---------------------------------------------------------------------------
;; The lens
;; ---------------------------------------------------------------------------

(def lens
  {:id              :patterns
   :description     "Patterns — recurring structural shapes (rule-of-three lift candidates)"
   :prompt-fragment
   (str "Pattern recurrence surfaces clusters of structurally-similar "
        "Affordances. The rule of three: if the same shape recurs 3+ times, "
        "consider abstracting it into a vocab lift. EXISTING lifts (in "
        "`fukan.canvas.vocab.*`) are the first place to look before "
        "inventing new vocabulary.\n\n"
        "For each cluster, weigh whether the recurrence is intentional "
        "repetition (intra-module symmetry, sister-module pattern) or an "
        "undiscovered abstraction. Surface a candidate vocab lift only "
        "when (a) the cluster has no existing lift covering it, (b) the "
        "recurrence isn't explainable as sister-module symmetry, and "
        "(c) the abstraction would actually improve readability — not "
        "all repetition is bad. When the cluster IS already covered by a "
        "vocab lift, the finding is a consistency check, not a call to "
        "act.")
   :compute compute
   :render  render})
