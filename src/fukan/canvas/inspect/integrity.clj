(ns fukan.canvas.inspect.integrity
  "Cross-reference integrity check — trust-tier feedback signal.

   Walks the canvas Datascript db and surfaces every reference that fails
   to land. Decision-ready output: every finding is `:severity :error`.
   A broken reference is an error under any methodology — no interpretive
   judgment required.

   Four checks (see DESIGN.md § \"Drift, coverage, and the close-drift loop\";
   original design: doc/plans/2026-05-26-feedback-signals-design.md § 1, git history):

     1. Unresolved `:references` Relations. Re-runs canvas-source segment
        matching against the db; any keyword target that fails to resolve
        becomes a `:inspect.integrity/unresolved-reference` finding.

     2. `:triggers` Relations whose target ent is not a `:canvas/rule`.
        Surfaces role mismatches (a `function`'s `(triggers X)` binds at
        construction time, but a later rename could leave the ref pointing
        at the wrong kind of affordance).

     3. `:emits` Relations whose target ent is not a `:canvas/event`.
        Symmetric with check 2.

     4. Cross-module shape-target keywords (in :affordance/input-types,
        :affordance/output-types, :type/field-types, :type/fields) that
        have a namespace but fail to resolve. Atomic types (no namespace)
        are intentionally not refs and are filtered out.

   Public API:

     (check canvas-db)
        Run all four checks. Returns a vector of finding maps.
        Returns [] when the db is clean."
  (:require [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.identity :as identity]
            [fukan.canvas.projection.canvas-source :as canvas-source]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- offender
  "Build an {:eid :stable-id} offender map for the given entity-id."
  [db eid]
  {:eid       eid
   :stable-id (identity/stable-id-for-eid db eid)})

(defn- ns-qualified?
  "True when k is a keyword carrying a namespace (i.e. a cross-module ref,
   not an atomic type like :String/:Integer/:Unit/:Any)."
  [k]
  (and (keyword? k) (some? (namespace k))))

;; ---------------------------------------------------------------------------
;; Check 1 — Unresolved :references Relations
;; ---------------------------------------------------------------------------

(defn check-references
  "Walk :references datoms; report keyword targets that fail to resolve via
   canvas-source segment matching. Returns a vector of finding maps."
  [db]
  (->> (d/datoms db :aevt :references)
       (keep (fn [datom]
               (let [from-eid (.-e datom)
                     target   (.-v datom)]
                 (when (and (keyword? target)
                            (nil? (canvas-source/resolve-reference-uuid db target)))
                   {:check     :inspect.integrity/unresolved-reference
                    :severity  :error
                    :message   (str "Reference " target
                                    " does not resolve to any module's child.")
                    :offenders [(offender db from-eid)]
                    :detail    {:target target}}))))
       vec))

;; ---------------------------------------------------------------------------
;; Check 2 — :triggers targets must have role :canvas/rule
;; ---------------------------------------------------------------------------

(defn check-triggers
  "Walk :triggers ref datoms; assert each target ent has :affordance/role
   :canvas/rule. Returns a vector of finding maps."
  [db]
  (->> (d/datoms db :aevt :triggers)
       (keep (fn [datom]
               (let [from-eid   (.-e datom)
                     target-eid (.-v datom)
                     target-ent (d/entity db target-eid)
                     role       (classification/direct-kind db target-eid)]
                 (when (not= role :canvas/rule)
                   {:check     :inspect.integrity/triggers-target-not-a-rule
                    :severity  :error
                    :message   (str "(triggers ...) target " (or (:entity/name target-ent) target-eid)
                                    " has role " (pr-str role)
                                    "; expected :canvas/rule.")
                    :offenders [(offender db from-eid)]
                    :detail    {:target-eid       target-eid
                                :target-stable-id (identity/stable-id-for-eid db target-eid)
                                :target-name      (:entity/name target-ent)
                                :actual-role      role
                                :expected-role    :canvas/rule}}))))
       vec))

;; ---------------------------------------------------------------------------
;; Check 3 — :emits targets must have role :canvas/event
;; ---------------------------------------------------------------------------

(defn check-emits
  "Walk :emits ref datoms; assert each target ent has :affordance/role
   :canvas/event. Returns a vector of finding maps."
  [db]
  (->> (d/datoms db :aevt :emits)
       (keep (fn [datom]
               (let [from-eid   (.-e datom)
                     target-eid (.-v datom)
                     target-ent (d/entity db target-eid)
                     role       (classification/direct-kind db target-eid)]
                 (when (not= role :canvas/event)
                   {:check     :inspect.integrity/emits-target-not-an-event
                    :severity  :error
                    :message   (str "(emits ...) target " (or (:entity/name target-ent) target-eid)
                                    " has role " (pr-str role)
                                    "; expected :canvas/event.")
                    :offenders [(offender db from-eid)]
                    :detail    {:target-eid       target-eid
                                :target-stable-id (identity/stable-id-for-eid db target-eid)
                                :target-name      (:entity/name target-ent)
                                :actual-role      role
                                :expected-role    :canvas/event}}))))
       vec))

;; ---------------------------------------------------------------------------
;; Check 4 — Cross-module shape-target keyword resolution
;; ---------------------------------------------------------------------------

(defn- shape-target-keywords
  "Yield a seq of [from-eid attr type-keyword] tuples for every namespaced
   keyword appearing in :affordance/input-types, :affordance/output-types,
   :type/field-types and :type/fields (where each :type/fields value is a
   [field-name-kw type-name-kw] tuple)."
  [db]
  (concat
    (for [a    [:affordance/input-types :affordance/output-types :type/field-types]
          d    (d/datoms db :aevt a)
          :let [k (.-v d)]
          :when (ns-qualified? k)]
      [(.-e d) a k])
    (for [d   (d/datoms db :aevt :type/fields)
          :let [v (.-v d)
                k (when (and (vector? v) (= 2 (count v))) (second v))]
          :when (ns-qualified? k)]
      [(.-e d) :type/fields k])))

(defn check-shape-targets
  "Walk shape-derived type-name datoms; report namespaced keywords that
   fail to resolve via canvas-source segment matching."
  [db]
  (->> (shape-target-keywords db)
       (keep (fn [[from-eid attr target]]
               (when (nil? (canvas-source/resolve-reference-uuid db target))
                 {:check     :inspect.integrity/unresolved-shape-target
                  :severity  :error
                  :message   (str "Shape references type " target
                                  " (via " attr ") which does not resolve to any module's child.")
                  :offenders [(offender db from-eid)]
                  :detail    {:target    target
                              :attribute attr}})))
       vec))

;; ---------------------------------------------------------------------------
;; Check 5 — Refinement-lattice well-formedness (the partition invariant)
;; ---------------------------------------------------------------------------

(defn- walk-refinement
  "Walk `tag`'s :refines chain via the `parent` map (tag → parent tag),
   guarding against the `known` tag set. Returns
   {:cycle? bool :dangling <tag-or-nil> :roots #{family-roots reached}}."
  [tag parent known]
  (loop [t tag, seen #{}, roots #{}]
    (let [roots (cond-> roots (classification/family-root? t) (conj t))]
      (cond
        (contains? seen t)           {:cycle? true :dangling nil :roots roots}
        (not (contains? parent t))   {:cycle? false :dangling nil :roots roots}
        (not (contains? known (parent t))) {:cycle? false :dangling (parent t) :roots roots}
        :else (recur (parent t) (conj seen t) roots)))))

(defn check-refinement
  "Assert the tag-definition refinement lattice is well-formed:
     - acyclic (no :refines chain loops),
     - no dangling parent (every :refines target is a registered tag), and
     - each tag reaches at most one family-root super-tag, so `family-of`
       stays single-valued (the partition invariant the legacy :entity/type
       index guaranteed by construction).
   Returns a vector of finding maps; [] when the lattice is well-formed."
  [db]
  (let [pairs  (d/q '[:find ?tag ?parent
                      :where [?td :tagdef/tag ?tag] [?td :tagdef/refines ?parent]] db)
        parent (into {} (map vec) pairs)
        known  (set (d/q '[:find [?tag ...] :where [?td :tagdef/tag ?tag]] db))]
    (->> (sort known)
         (keep (fn [tag]
                 (let [{:keys [cycle? dangling roots]} (walk-refinement tag parent known)]
                   (cond
                     cycle?
                     {:check     :inspect.integrity/refinement-cycle
                      :severity  :error
                      :message   (str "Tag " tag " has a cyclic :refines chain.")
                      :offenders []
                      :detail    {:tag tag}}

                     dangling
                     {:check     :inspect.integrity/refinement-dangling-parent
                      :severity  :error
                      :message   (str "Tag " tag " refines " dangling
                                      ", which is not a registered tag.")
                      :offenders []
                      :detail    {:tag tag :parent dangling}}

                     (> (count roots) 1)
                     {:check     :inspect.integrity/refinement-multiple-families
                      :severity  :error
                      :message   (str "Tag " tag " reaches multiple family roots "
                                      (pr-str (sort roots))
                                      "; family-of would be multi-valued.")
                      :offenders []
                      :detail    {:tag tag :roots roots}}))))
         vec)))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn check
  "Run all integrity checks against the canvas db. Returns a vector of
   finding maps. Returns [] when the db is clean.

   Decision-ready output: every finding is `:severity :error`."
  [canvas-db]
  (into []
        (concat
          (check-references    canvas-db)
          (check-triggers      canvas-db)
          (check-emits         canvas-db)
          (check-shape-targets canvas-db)
          (check-refinement    canvas-db))))
