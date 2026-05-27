(ns fukan.canvas.lens.tar-pit
  "Tar-Pit lens — a THEORETICAL lens; the first non-structural lens of the
   substrate. Frames the canvas through Moseley & Marks (2006) \"Out of the
   Tar Pit\": essential complexity (inherent to the problem domain,
   irreducible) vs accidental complexity (introduced by representation
   choices, control flow, mutable state, infrastructure — reducible).

   Unlike the structural lenses (patterns, consistency), the load-bearing
   artifact here is the `:prompt-fragment`. The `:compute` fn is a SLICE
   EXTRACTOR, not a findings producer — it pulls the canvas data the LLM
   needs to apply the Tar-Pit framework. The LLM does the interpretation.

   The substrate must accept this lens shape with zero core/registry/survey
   changes; if it doesn't, that's the substrate friction the task is meant
   to surface.

   See doc/plans/2026-05-26-canvas-substrate-phase-5.md
   § Phase 5, Task 10 — Tar-pit lens."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

;; ---------------------------------------------------------------------------
;; Defaults + opts
;; ---------------------------------------------------------------------------

(def ^:private default-max-members 50)

(defn- opts->max-members [opts]
  (or (:max-members opts) default-max-members))

(defn- opts->include-effects? [opts]
  (let [v (:include-effects? opts)]
    (if (nil? v) true v)))

;; ---------------------------------------------------------------------------
;; Slice extraction — queries
;; ---------------------------------------------------------------------------

(defn- module-name-for-entity
  "Return the module-name string for the module owning entity eid, or nil."
  [db eid]
  (ffirst (d/q '[:find ?mn
                 :in $ ?c
                 :where [?m :module/child ?c]
                        [?m :entity/name ?mn]]
               db eid)))

(defn- stable-id-for
  "Lightweight stable-id derivation that does not require requiring
   `fukan.canvas.identity` (and so does not pull a dependency cycle if a
   future projection layer reads the lens). Mirrors identity/stable-id."
  [entity-type module-name entity-name]
  (case entity-type
    :Module     module-name
    :Affordance (str module-name "/" entity-name)
    :State      (str module-name "/state/" entity-name)
    :Type       (str module-name "/type/" entity-name)
    (str module-name "/" (name entity-type) "/" entity-name)))

(defn- record-types
  "Record-shaped Types — candidate essential state. Each entry carries
   the type's field list as a vector of [field-name field-type] pairs."
  [db]
  (->> (d/q '[:find ?e ?n
              :where [?e :entity/type :Type]
                     [?e :entity/name ?n]
                     [?e :type/fields _]]
            db)
       (map (fn [[eid n]]
              (let [mn      (module-name-for-entity db eid)
                    fields  (->> (d/q '[:find ?v
                                        :in $ ?e
                                        :where [?e :type/fields ?v]]
                                      db eid)
                                 (map first)
                                 (filter (fn [v] (and (vector? v) (= 2 (count v)))))
                                 (sort-by first)
                                 vec)]
                {:stable-id (stable-id-for :Type mn n)
                 :name      n
                 :module    mn
                 :fields    fields})))
       (sort-by :stable-id)
       vec))

(defn- atomic-types
  "Atomic (opaque value) Types — candidate essential state of an
   indivisible kind (statuses, ids, opaque tokens)."
  [db]
  (->> (d/q '[:find ?e ?n
              :where [?e :entity/type :Type]
                     [?e :entity/name ?n]
                     (not [?e :type/fields _])]
            db)
       (map (fn [[eid n]]
              (let [mn (module-name-for-entity db eid)]
                {:stable-id (stable-id-for :Type mn n)
                 :name      n
                 :module    mn})))
       (sort-by :stable-id)
       vec))

(defn- getter-affordances
  "Affordances with role :canvas/getter — first-class state-access points
   (zero-arg, Optional<T> output). The shape's inner type, when present,
   is surfaced as :returns."
  [db]
  (->> (d/q '[:find ?e ?n
              :where [?e :entity/type :Affordance]
                     [?e :affordance/role :canvas/getter]
                     [?e :entity/name ?n]]
            db)
       (map (fn [[eid n]]
              (let [mn      (module-name-for-entity db eid)
                    outs    (->> (d/q '[:find ?t
                                        :in $ ?e
                                        :where [?e :affordance/output-types ?t]]
                                      db eid)
                                 (map first)
                                 (sort)
                                 vec)]
                {:stable-id (stable-id-for :Affordance mn n)
                 :name      n
                 :module    mn
                 :returns   outs})))
       (sort-by :stable-id)
       vec))

(defn- function-effects
  "Return the set of effect keywords declared by function eid, via the
   :fukan.canvas.monolith/performs Relation kind."
  [db eid]
  (->> (d/q '[:find ?effect
              :in $ ?e
              :where [?e :fukan.canvas.monolith/performs ?effect]]
            db eid)
       (map first)
       (sort)
       vec))

(defn- function-affordances
  "All Affordances with role :fukan.canvas.monolith/exposed-call. Split into
   :pure (no :fukan.canvas.monolith/performs relation) and :effectful
   (one or more). Each entry carries inputs/outputs and (for effectful
   functions) the effect-keyword list."
  [db]
  (->> (d/q '[:find ?e ?n
              :where [?e :entity/type :Affordance]
                     [?e :affordance/role :fukan.canvas.monolith/exposed-call]
                     [?e :entity/name ?n]]
            db)
       (map (fn [[eid n]]
              (let [mn       (module-name-for-entity db eid)
                    inputs   (->> (d/q '[:find ?t
                                         :in $ ?e
                                         :where [?e :affordance/input-types ?t]]
                                       db eid)
                                  (map first) sort vec)
                    outputs  (->> (d/q '[:find ?t
                                         :in $ ?e
                                         :where [?e :affordance/output-types ?t]]
                                       db eid)
                                  (map first) sort vec)
                    effects  (function-effects db eid)]
                {:stable-id    (stable-id-for :Affordance mn n)
                 :name         n
                 :module       mn
                 :inputs       inputs
                 :outputs      outputs
                 :effects      effects
                 :has-effects? (boolean (seq effects))})))
       (sort-by :stable-id)))

(defn- declarative-rules
  "Affordances with role :canvas/invariant or :canvas/rule — the
   declarative behavioural surface (timeless commitments + reactive rules)."
  [db]
  (->> (d/q '[:find ?e ?n ?r
              :where [?e :entity/type :Affordance]
                     [?e :affordance/role ?r]
                     [(contains? #{:canvas/invariant :canvas/rule} ?r)]
                     [?e :entity/name ?n]]
            db)
       (map (fn [[eid n r]]
              (let [mn (module-name-for-entity db eid)]
                {:stable-id (stable-id-for :Affordance mn n)
                 :name      n
                 :module    mn
                 :role      r})))
       (sort-by :stable-id)
       vec))

(defn- total-modules [db]
  (or (ffirst (d/q '[:find (count ?e)
                     :where [?e :entity/type :Module]]
                   db))
      0))

;; ---------------------------------------------------------------------------
;; Truncation
;; ---------------------------------------------------------------------------

(defn- truncate
  "Cap a category at `max-n`. Returns {:items <bounded vec> :total <orig n>
   :truncated? <bool>}. Preserves the original total so the render can show
   '... and N more'."
  [items max-n]
  (let [n (count items)
        bounded (vec (take max-n items))]
    {:items      bounded
     :total      n
     :truncated? (> n max-n)}))

;; ---------------------------------------------------------------------------
;; Scope summary
;; ---------------------------------------------------------------------------

(defn- ratio [num denom]
  (if (zero? denom) 0.0 (double (/ num denom))))

(defn- scope-summary
  [db pure-count effect-count state-count behavior-count]
  (let [modules    (total-modules db)
        all-fns    (+ pure-count effect-count)
        total-ents (+ state-count behavior-count)]
    {:total-modules   modules
     :state-fraction  (ratio state-count total-ents)
     :pure-fraction   (ratio pure-count all-fns)
     :effect-fraction (ratio effect-count all-fns)}))

;; ---------------------------------------------------------------------------
;; Compute — slice extractor
;; ---------------------------------------------------------------------------

(defn compute
  "Extract the canvas slice the LLM needs to apply the Tar-Pit framework.

   `opts`:

     :max-members      <int>   ; cap per-category list size (default 50)
     :include-effects? <bool>  ; when false, omits :effectful-functions
                                ; from the slice (default true)

   Returns a slice map of shape:

     {:state-candidates
        {:types-with-fields  {:items [...] :total N :truncated? bool}
         :atomic-types       {:items [...] :total N :truncated? bool}
         :getters            {:items [...] :total N :truncated? bool}}
      :behavior
        {:pure-functions      {:items [...] :total N :truncated? bool}
         :effectful-functions {:items [...] :total N :truncated? bool}   ; or :omitted true
         :declarative-rules   {:items [...] :total N :truncated? bool}}
      :scope-summary
        {:total-modules N
         :state-fraction <ratio>
         :pure-fraction  <ratio>
         :effect-fraction <ratio>}}

   The structure is INPUT FOR THE LLM, not output for a user. The lens's
   prompt-fragment frames how the LLM is to interpret it."
  [canvas-db opts]
  (let [max-n (opts->max-members opts)
        include-effects? (opts->include-effects? opts)
        recs   (record-types canvas-db)
        atoms  (atomic-types canvas-db)
        gets   (getter-affordances canvas-db)
        fns    (function-affordances canvas-db)
        pures  (filterv (complement :has-effects?) fns)
        effs   (filterv :has-effects? fns)
        rules  (declarative-rules canvas-db)
        state-count    (+ (count recs) (count atoms) (count gets))
        behavior-count (+ (count fns)  (count rules))]
    {:state-candidates
     {:types-with-fields (truncate recs  max-n)
      :atomic-types      (truncate atoms max-n)
      :getters           (truncate gets  max-n)}
     :behavior
     (cond-> {:pure-functions    (truncate pures max-n)
              :declarative-rules (truncate rules max-n)}
       include-effects?       (assoc :effectful-functions (truncate effs max-n))
       (not include-effects?) (assoc :effectful-functions {:omitted true
                                                           :total   (count effs)}))
     :scope-summary
     (scope-summary canvas-db (count pures) (count effs)
                    state-count behavior-count)}))

;; ---------------------------------------------------------------------------
;; Prompt fragment — load-bearing artifact
;; ---------------------------------------------------------------------------

(def prompt-fragment
  (str
    "## Out of the Tar Pit — essential vs accidental complexity\n\n"
    "Moseley & Marks (2006) draw a load-bearing distinction: **essential "
    "complexity** is the irreducible difficulty inherent in the problem "
    "domain (the orders, the routing rules, the transactions a system must "
    "track); **accidental complexity** is everything else — control flow, "
    "mutable state introduced for performance, infrastructure plumbing, "
    "representation choices, framework wiring. Their thesis is that most "
    "software bloat is accidental, and that design quality is largely about "
    "minimising accidental complexity by representing essential state as "
    "pure relational data, essential logic as pure functions, and confining "
    "accidental complexity (the \"feeders and observers\") to the edges.\n\n"
    "The canvas slice below is the design surface this lens hands you. "
    "Apply the Tar-Pit framework as ANALYSIS, not as a refactoring plan. "
    "Three concrete questions to answer in turn:\n\n"
    "1. **Which `:state-candidates` represent essential state vs accidental "
    "representation?** A record like `Order` is plausibly essential; a "
    "record like `RenderingContext` or `BuildState` is more likely "
    "accidental — it exists because of HOW we compute, not WHAT we must "
    "remember. Walk the records, the atomic types, and the getters; for "
    "each one, ask whether it would still exist in a maximally-declarative "
    "rewrite of the same problem. Flag the ones that would not.\n\n"
    "2. **Where do effects cluster among `:behavior`?** What fraction of "
    "functions is pure derivation from state vs effectful (carries a "
    "`(effect …)` declaration)? Where do effects concentrate — are they "
    "isolated at the edges (a few feeder/observer modules) or scattered "
    "across the core? For any effectful function, ask: could the work "
    "split into a pure derivation + a thin effect-emitter? Identify the "
    "1–3 strongest split candidates if any exist.\n\n"
    "3. **What do `:declarative-rules` (invariants + rules) commit to?** "
    "Are these expressing essential domain logic (\"every order has a "
    "non-empty customer\") or accidental wiring (\"view re-renders on "
    "selection change\")? Declarative rules in the canvas are the most "
    "Tar-Pit-aligned shape; their presence is evidence the design has "
    "been thinking in this register. Their absence on essential domains "
    "is a signal worth noting.\n\n"
    "**Guideline — the FRP prescription as an angle of attack, not a "
    "verdict.** The paper advocates Functional Relational Programming: "
    "essential state as relational data; essential logic as pure functions; "
    "accidental complexity isolated at the edges; mutable state only when "
    "justified. The lens is NOT claiming the canvas should be FRP — fukan's "
    "canvas is a design substrate, not a runtime — but the framework is a "
    "useful angle of attack on whether the design is honouring the "
    "essential/accidental distinction.\n\n"
    "**What NOT to do.** Do not propose specific canvas edits, do not "
    "rewrite types or functions, do not refactor effects. Produce a "
    "STRUCTURED ANALYSIS the human reads and decides on. The lens output "
    "is an analytic frame, not an action plan. Three short sections — one "
    "per question — answered with concrete references to entities in the "
    "slice. That is the deliverable."))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn- pct [r]
  (format "%.1f%%" (* 100.0 (double r))))

(defn- render-member [{:keys [stable-id]}]
  (str "  - " stable-id))

(defn- render-function-member [{:keys [stable-id effects]}]
  (if (seq effects)
    (str "  - " stable-id " (effects: " (str/join " " (map pr-str effects)) ")")
    (str "  - " stable-id)))

(defn- render-category
  "Render one category as a bullet list with `... and N more` trailer when
   truncated."
  [label render-fn {:keys [items total truncated?]}]
  (let [hdr   (str "**" label "** (" total
                   (if truncated?
                     (str ", showing " (count items))
                     "")
                   ")")]
    (if (zero? total)
      (str hdr "\n  - _none_")
      (str hdr "\n"
           (str/join "\n" (map render-fn items))
           (when truncated?
             (str "\n  - … and " (- total (count items)) " more"))))))

(defn- render-omitted [label total]
  (str "**" label "** (" total ", omitted via :include-effects? false)"))

(defn render
  "Synthesize prompt-fragment + slice into a markdown analysis document
   the LLM consumes."
  [findings opts]
  (let [{:keys [state-candidates behavior scope-summary]
         :or   {state-candidates {}
                behavior         {}
                scope-summary    {}}} (or findings {})
        {:keys [types-with-fields atomic-types getters]
         :or   {types-with-fields {:items [] :total 0}
                atomic-types      {:items [] :total 0}
                getters           {:items [] :total 0}}}
        state-candidates
        {:keys [pure-functions effectful-functions declarative-rules]
         :or   {pure-functions     {:items [] :total 0}
                effectful-functions {:items [] :total 0}
                declarative-rules  {:items [] :total 0}}}
        behavior
        include-effects? (opts->include-effects? (or opts {}))
        {:keys [total-modules state-fraction pure-fraction effect-fraction]
         :or   {total-modules 0 state-fraction 0.0
                pure-fraction 0.0 effect-fraction 0.0}}
        scope-summary]
    (str
      "## Tar-Pit Analysis\n\n"
      prompt-fragment
      "\n\n"
      "### Canvas slice\n\n"
      "**State candidates:**\n\n"
      (render-category "Record types with fields" render-member types-with-fields) "\n\n"
      (render-category "Atomic (opaque) types"     render-member atomic-types)      "\n\n"
      (render-category "Getters (state-access points)" render-member getters)       "\n\n"
      "**Behavior:**\n\n"
      (render-category "Pure functions" render-function-member pure-functions) "\n\n"
      (if include-effects?
        (render-category "Effectful functions" render-function-member effectful-functions)
        (render-omitted "Effectful functions" (:total effectful-functions 0))) "\n\n"
      (render-category "Declarative rules / invariants" render-member declarative-rules) "\n\n"
      "**Scope summary:**\n\n"
      "- Modules: "         total-modules "\n"
      "- State fraction: "  (pct state-fraction) "\n"
      "- Pure-function fraction: "  (pct pure-fraction) "\n"
      "- Effect fraction: " (pct effect-fraction) "\n\n"
      "### Questions for the LLM (re-stated from prompt-fragment)\n\n"
      "1. Which state candidates are essential vs accidental representation?\n"
      "2. Where do effects cluster? Could any effectful function split into "
      "a pure derivation + a thin effect-emitter?\n"
      "3. Do the declarative rules/invariants encode essential domain logic "
      "or accidental wiring?")))

;; ---------------------------------------------------------------------------
;; The lens
;; ---------------------------------------------------------------------------

(def lens
  {:id              :tar-pit
   :description     "Tar-Pit — essential vs accidental complexity (Moseley & Marks 2006)"
   :prompt-fragment prompt-fragment
   :compute         compute
   :render          render})
