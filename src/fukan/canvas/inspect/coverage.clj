(ns fukan.canvas.inspect.coverage
  "Coverage analysis — trust-tier feedback signal.

   Surfaces structural coverage gaps: dead canvas content, substrate-floating
   entities, exports nobody consumes, modules without declared APIs, rules
   that nothing triggers, events that nothing handles.

   Decision-ready output, but with an in-band `:severity` field on every
   finding so callers can filter (the LLM treats `:error` as factual; the
   user reads `:warning`/`:info` as decision-ready but inviting context-check).

   Severity ladder (see DESIGN.md § \"Drift, coverage, and the close-drift loop\";
   original design: doc/plans/2026-05-26-canvas-substrate-phase-5.md Sprint 3 Task 7, git history):

     :error    — structural impossibility (unreached entity floating outside
                 any module's :module/child graph)
     :warning  — likely real issue but might be intentional (orphan entity,
                 exported-but-unreferenced, rule without trigger, event
                 without handler)
     :info     — observational, likely intentional in many cases (module
                 without exports)

   Six checks:

     1. orphan-entity              :warning  — entity has zero incoming refs
                                                of any kind. Skips Modules,
                                                `:canvas/exported` entities, and
                                                affordances whose role is
                                                wired by mechanism rather
                                                than by the ref graph (see
                                                `orphan-exempt-roles`).
     2. unreached-entity           :error    — entity not reachable from any
                                                Module via :module/child
     3. exported-but-unreferenced  :warning  — `:canvas/exported` entity that no
                                                OTHER module references
     4. module-without-exports     :info     — Module whose children carry
                                                no `:canvas/exported` tag
     5. rule-without-trigger       :warning  — `:canvas/rule` affordance with
                                                no inbound :triggers
     6. event-without-handler      :warning  — `:canvas/event` affordance with
                                                no `:canvas/handler` declaring
                                                `(on …)` against it

   Public API:

     (check canvas-db)
        Run all six checks. Returns a vector of finding maps; [] when
        coverage is full. Callers filter by :severity."
  (:require [datascript.core :as d]
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

(defn- entity-eids
  "All entity eids of the given :entity/type."
  [db etype]
  (->> (d/datoms db :aevt :entity/type)
       (filter #(= etype (.-v %)))
       (map #(.-e %))))

(defn- non-module-entity-eids
  "All entity eids that are not Modules."
  [db]
  (->> (d/datoms db :aevt :entity/type)
       (keep (fn [d]
               (when (not= :Module (.-v d))
                 (.-e d))))))

(defn- has-tag?
  [db eid tag]
  (->> (d/datoms db :eavt eid :entity/tag)
       (some #(= tag (.-v %)))))

(defn- owning-module-eid
  "Return the eid of the Module owning entity-eid via :module/child, or nil."
  [db entity-eid]
  (ffirst (d/q '[:find ?m
                 :in $ ?c
                 :where [?m :module/child ?c]
                        [?m :entity/type :Module]]
               db entity-eid)))

(defn- module-of
  "Return the module eid for any entity eid (self if Module, owner if child)."
  [db eid]
  (let [t (->> (d/datoms db :eavt eid :entity/type) first (#(some-> % .-v)))]
    (if (= :Module t)
      eid
      (owning-module-eid db eid))))

(defn- ref-keyword-resolved-eids
  "For every keyword-valued :references datom, resolve the keyword to a target
   entity eid (via canvas-source segment matching). Returns a set of eids that
   are pointed at by at least one keyword reference."
  [db]
  (let [uuid->eid (into {} (map (fn [d] [(.-v d) (.-e d)])
                                (d/datoms db :aevt :entity/id)))]
    (into #{}
          (keep (fn [d]
                  (let [v (.-v d)]
                    (when (keyword? v)
                      (when-let [uuid (canvas-source/resolve-reference-uuid db v)]
                        (get uuid->eid uuid))))))
          (d/datoms db :aevt :references))))

(defn- shape-target-resolved-eids
  "For every namespaced keyword appearing in shape-target attrs
   (:affordance/input-types, :affordance/output-types, :type/field-types,
   :type/fields), resolve to a target entity eid. Returns the SET of pointed-at
   eids paired with the source eid so we can also distinguish self-references."
  [db]
  (let [uuid->eid (into {} (map (fn [d] [(.-v d) (.-e d)])
                                (d/datoms db :aevt :entity/id)))
        resolve   (fn [kw]
                    (when (ns-qualified? kw)
                      (when-let [uuid (canvas-source/resolve-reference-uuid db kw)]
                        (get uuid->eid uuid))))]
    (concat
      (for [a    [:affordance/input-types :affordance/output-types :type/field-types]
            d    (d/datoms db :aevt a)
            :let [k (.-v d)
                  target (resolve k)]
            :when target]
        [(.-e d) target])
      (for [d   (d/datoms db :aevt :type/fields)
            :let [v (.-v d)
                  k (when (and (vector? v) (= 2 (count v))) (second v))
                  target (resolve k)]
            :when target]
        [(.-e d) target]))))

;; ---------------------------------------------------------------------------
;; Check 1 — Orphan entities (no incoming references)
;; ---------------------------------------------------------------------------

(def ^:private orphan-exempt-roles
  "Affordance roles whose mechanism does not run through the ref graph.
   Orphan-check skips these so the warning only fires on entities that
   genuinely SHOULD be wired into the graph but aren't.

   - :canvas/invariant  — timeless commitments, semantic not structural.
   - :canvas/checker    — wired by the validation phase, not by :triggers/:references.
   - :canvas/getter     — wired by the lifecycle mechanism.
   - :canvas/rule       — coverage gap is tracked more precisely by
                          `rule-without-trigger`; double-flagging as orphan
                          is noise.
   - :canvas/event      — coverage gap is tracked more precisely by
                          `event-without-handler`."
  #{:canvas/invariant
    :canvas/checker
    :canvas/getter
    :canvas/rule
    :canvas/event})

(defn- incoming-ref-eids
  "Set of every eid pointed at by some incoming reference (excluding
   :module/child ownership)."
  [db]
  (let [trigger-targets (set (map #(.-v %) (d/datoms db :aevt :triggers)))
        emit-targets    (set (map #(.-v %) (d/datoms db :aevt :emits)))
        ref-targets     (ref-keyword-resolved-eids db)
        shape-targets   (set (map second (shape-target-resolved-eids db)))]
    (into #{} (concat trigger-targets emit-targets ref-targets shape-targets))))

(defn check-orphans
  "Affordances and Types with no incoming references. Skips Modules,
   :canvas/exported entities, and affordances whose role is in
   `orphan-exempt-roles` (mechanism-driven roles whose orphan-ness is
   either expected idiom or tracked by a more specific check)."
  [db]
  (let [pointed (incoming-ref-eids db)]
    (->> (non-module-entity-eids db)
         (remove pointed)
         (remove #(has-tag? db % :canvas/exported))
         (remove #(contains? orphan-exempt-roles
                             (:affordance/role (d/entity db %))))
         (map (fn [eid]
                (let [ent  (d/entity db eid)
                      etype (:entity/type ent)
                      ename (:entity/name ent)
                      mod-eid (owning-module-eid db eid)
                      mod-name (when mod-eid (:entity/name (d/entity db mod-eid)))]
                  {:check     :inspect.coverage/orphan-entity
                   :severity  :warning
                   :message   (str (name (or etype :Entity))
                                   " " ename
                                   (when mod-name (str " in " mod-name))
                                   " has no incoming references.")
                   :offenders [(offender db eid)]
                   :detail    {:entity-type etype
                               :role        (:affordance/role ent)}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 2 — Unreached entities (substrate-floating)
;; ---------------------------------------------------------------------------

(defn check-unreached
  "Non-Module entities not reachable from any Module via :module/child."
  [db]
  (let [owned (into #{} (map #(.-v %) (d/datoms db :aevt :module/child)))]
    (->> (non-module-entity-eids db)
         (remove owned)
         (map (fn [eid]
                (let [ent   (d/entity db eid)
                      etype (:entity/type ent)
                      ename (:entity/name ent)]
                  {:check     :inspect.coverage/unreached-entity
                   :severity  :error
                   :message   (str (name (or etype :Entity))
                                   " " ename
                                   " is not owned by any module's :module/child graph.")
                   :offenders [(offender db eid)]
                   :detail    {:entity-type etype}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 3 — Exported but never referenced externally
;; ---------------------------------------------------------------------------

(defn- ref-keyword-source-target-pairs
  "Pairs [source-eid target-eid] for every keyword-resolvable :references datom."
  [db]
  (let [uuid->eid (into {} (map (fn [d] [(.-v d) (.-e d)])
                                (d/datoms db :aevt :entity/id)))]
    (keep (fn [d]
            (let [v (.-v d)]
              (when (keyword? v)
                (when-let [uuid (canvas-source/resolve-reference-uuid db v)]
                  (when-let [tgt (get uuid->eid uuid)]
                    [(.-e d) tgt])))))
          (d/datoms db :aevt :references))))

(defn- incoming-ref-source-target-pairs
  "Yield [source-eid target-eid] pairs across all inbound-reference channels."
  [db]
  (concat
    (map (fn [d] [(.-e d) (.-v d)]) (d/datoms db :aevt :triggers))
    (map (fn [d] [(.-e d) (.-v d)]) (d/datoms db :aevt :emits))
    (ref-keyword-source-target-pairs db)
    (shape-target-resolved-eids db)))

(defn check-exported-but-unreferenced
  "Entities tagged :canvas/exported but referenced only from within their own module
   (or not at all). Same-module references don't count — exports are for
   cross-module consumption."
  [db]
  (let [pairs        (incoming-ref-source-target-pairs db)
        ext-targets  (into #{}
                           (keep (fn [[src tgt]]
                                   (let [src-mod (module-of db src)
                                         tgt-mod (module-of db tgt)]
                                     (when (and src-mod tgt-mod
                                                (not= src-mod tgt-mod))
                                       tgt))))
                           pairs)
        exported-eids (->> (d/datoms db :aevt :entity/tag)
                           (filter #(= :canvas/exported (.-v %)))
                           (map #(.-e %)))]
    (->> exported-eids
         (remove ext-targets)
         (map (fn [eid]
                (let [ent    (d/entity db eid)
                      etype  (:entity/type ent)
                      ename  (:entity/name ent)
                      mod-eid (owning-module-eid db eid)
                      mod-name (when mod-eid (:entity/name (d/entity db mod-eid)))]
                  {:check     :inspect.coverage/exported-but-unreferenced
                   :severity  :warning
                   :message   (str (name (or etype :Entity))
                                   " " ename
                                   (when mod-name (str " in " mod-name))
                                   " is exported but never referenced from another module.")
                   :offenders [(offender db eid)]
                   :detail    {:entity-type etype}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 4 — Modules without (exports …) declarations
;; ---------------------------------------------------------------------------

(defn check-modules-without-exports
  "Modules whose children carry no :canvas/exported tag."
  [db]
  (let [exported-eids (into #{}
                            (->> (d/datoms db :aevt :entity/tag)
                                 (filter #(= :canvas/exported (.-v %)))
                                 (map #(.-e %))))]
    (->> (entity-eids db :Module)
         (filter (fn [mod-eid]
                   (let [children (->> (d/datoms db :eavt mod-eid :module/child)
                                       (map #(.-v %)))]
                     (and (seq children)
                          (not-any? exported-eids children)))))
         (map (fn [mod-eid]
                (let [mod-name (:entity/name (d/entity db mod-eid))]
                  {:check     :inspect.coverage/module-without-exports
                   :severity  :info
                   :message   (str "Module " mod-name
                                   " declares no (exports …) — no child carries the :canvas/exported tag.")
                   :offenders [(offender db mod-eid)]
                   :detail    {:module-name mod-name}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 5 — Rules with no triggering function
;; ---------------------------------------------------------------------------

(defn check-rules-without-trigger
  "Affordances of role :canvas/rule that no `(triggers …)` ref points at."
  [db]
  (let [triggered (set (map #(.-v %) (d/datoms db :aevt :triggers)))
        rule-eids (->> (d/datoms db :aevt :affordance/role)
                       (filter #(= :canvas/rule (.-v %)))
                       (map #(.-e %)))]
    (->> rule-eids
         (remove triggered)
         (map (fn [eid]
                (let [ent   (d/entity db eid)
                      ename (:entity/name ent)
                      mod-eid (owning-module-eid db eid)
                      mod-name (when mod-eid (:entity/name (d/entity db mod-eid)))]
                  {:check     :inspect.coverage/rule-without-trigger
                   :severity  :warning
                   :message   (str "Rule " ename
                                   (when mod-name (str " in " mod-name))
                                   " has no function declaring (triggers " ename ").")
                   :offenders [(offender db eid)]
                   :detail    {:role :canvas/rule}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 6 — Events with no handler
;; ---------------------------------------------------------------------------

(defn- handler-eids
  [db]
  (->> (d/datoms db :aevt :affordance/role)
       (filter #(= :canvas/handler (.-v %)))
       (map #(.-e %))))

(defn- handled-event-eids
  "Set of event eids that some :canvas/handler points at via :references
   (the handler's `(on <event>)` form emits a :references datom). We only
   count :references datoms emitted from a handler affordance."
  [db]
  (let [handlers   (into #{} (handler-eids db))
        uuid->eid  (into {} (map (fn [d] [(.-v d) (.-e d)])
                                 (d/datoms db :aevt :entity/id)))]
    (into #{}
          (keep (fn [d]
                  (when (contains? handlers (.-e d))
                    (let [v (.-v d)]
                      (when (keyword? v)
                        (when-let [uuid (canvas-source/resolve-reference-uuid db v)]
                          (get uuid->eid uuid)))))))
          (d/datoms db :aevt :references))))

(defn check-events-without-handler
  "Affordances of role :canvas/event that no `:canvas/handler` declares
   `(on <event>)` against."
  [db]
  (let [handled    (handled-event-eids db)
        event-eids (->> (d/datoms db :aevt :affordance/role)
                        (filter #(= :canvas/event (.-v %)))
                        (map #(.-e %)))]
    (->> event-eids
         (remove handled)
         (map (fn [eid]
                (let [ent   (d/entity db eid)
                      ename (:entity/name ent)
                      mod-eid (owning-module-eid db eid)
                      mod-name (when mod-eid (:entity/name (d/entity db mod-eid)))]
                  {:check     :inspect.coverage/event-without-handler
                   :severity  :warning
                   :message   (str "Event " ename
                                   (when mod-name (str " in " mod-name))
                                   " has no handler declaring (on …) against it.")
                   :offenders [(offender db eid)]
                   :detail    {:role :canvas/event}})))
         vec)))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn check
  "Run all coverage checks against the canvas db. Returns a vector of finding
   maps. Returns [] when coverage is full.

   Each finding carries :severity {:error :warning :info} for caller filtering."
  [canvas-db]
  (into []
        (concat
          (check-orphans                   canvas-db)
          (check-unreached                 canvas-db)
          (check-exported-but-unreferenced canvas-db)
          (check-modules-without-exports   canvas-db)
          (check-rules-without-trigger     canvas-db)
          (check-events-without-handler    canvas-db))))
