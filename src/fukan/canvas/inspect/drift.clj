(ns fukan.canvas.inspect.drift
  "Drift detection — trust-tier feedback signal across canvas ↔ code.

   Canvas declares design intent; src/ holds implementation. Over time the
   two diverge — a canvas function declared but never written, an invariant
   declared with no fn enforcing it, a rule with no realising code. The
   Clojure Target Analyzer already tags each projection edge with
   `:validity {:valid | :absent}`; this helper reads those edges and turns
   `:absent` into structured, decision-ready findings.

   Every finding is `:severity :warning` — drift is fact-of-discrepancy,
   resolution is judgment. Both sides of the discrepancy are named so the
   LLM can weigh whether the canvas should move (drop the declaration) or
   the code should move (add the implementation).

   Phase 6 Task 6 ships the missing-implementation umbrella check. Because
   Phase 6 Sprint 2 unified the projection path across functions, events,
   invariants, rules, getters and checkers, ONE check fn handles all
   projected entity kinds uniformly — no per-category branching.

   Task 7 will add a shape-drift check for records (canvas field-list vs
   defrecord/Malli field-list).

   Public API:

     (check model)
        Run all drift checks against the loaded Model. Returns a vector
        of finding maps; [] when every canvas declaration has a matching
        code-side counterpart."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- projects-edge?
  "True when the edge is a :relation/projects edge."
  [edge]
  (= :relation/projects (:kind edge)))

(defn- absent?
  "True when the edge's :validity tag is :absent."
  [edge]
  (= :absent (:validity edge)))

(defn- canvas-role->canvas-kind
  "Map an affordance's `:canvas-role` keyword to the short `:canvas-kind`
   tag used in finding offenders. Falls back to :function for any unknown
   role under :primitive/operation."
  [role]
  (case role
    :canvas/operation  :function
    :canvas/rule       :rule
    :canvas/invariant  :invariant
    :canvas/event      :event
    :canvas/getter     :getter
    :canvas/checker    :checker
    :canvas/handler    :handler
    nil))

(defn- projection-kind->canvas-kind
  "Infer a canvas-kind from a projection-kind metadata keyword when the
   primitive's `:canvas-role` isn't carried (e.g. Type primitives don't
   ship :canvas-role today)."
  [pk]
  (case pk
    :projection-kind/operation :function
    :projection-kind/rule      :rule
    :projection-kind/invariant :invariant
    :projection-kind/schema    :type
    :projection-kind/test      :test
    :unknown))

(defn- infer-canvas-kind
  "Derive a `:canvas-kind` tag for the finding, preferring the primitive's
   `:canvas-role` (set by canvas-source for affordances) and falling back
   to the edge's `:projection-kind`."
  [primitive projection-kind]
  (or (canvas-role->canvas-kind (:canvas-role primitive))
      (projection-kind->canvas-kind projection-kind)))

(defn- module-coord-from-stable-id
  "Stable-ids use '/' as separator: 'module/name', 'module/type/Name',
   'module/state/name'. The leading segment is the module coord."
  [stable-id]
  (when (string? stable-id)
    (if (str/includes? stable-id "/")
      (first (str/split stable-id #"/" 2))
      stable-id)))

(defn- ns->file-path
  "Convert a Clojure namespace to a conventional source file path under
   src/. Replaces '.' with '/' and '-' with '_'. The address registry's
   ns format already matches the kebab-cased source layout, so the only
   thing this fn does is the dot/hyphen swap."
  [ns-string]
  (when (string? ns-string)
    (str "src/"
         (-> ns-string
             (str/replace "." "/")
             (str/replace "-" "_"))
         ".clj")))

(defn- qualified-name->parts
  "Split 'ns/name' into [ns-string name-string]. Returns nil on malformed
   input."
  [qname]
  (when (and (string? qname) (str/includes? qname "/"))
    (let [idx (str/last-index-of qname "/")]
      [(subs qname 0 idx) (subs qname (inc idx))])))

;; ---------------------------------------------------------------------------
;; Check 1 — Missing implementation (umbrella across all projected kinds)
;; ---------------------------------------------------------------------------

(defn- absent-edge->finding
  "Turn one `:validity :absent` projects edge into a finding map. The model
   carries the primitive on the `:from` side and the (expected) code
   artifact id on the `:to` side."
  [model edge]
  (let [primitive-id  (-> edge :from :id)
        primitive     (get-in model [:primitives primitive-id])
        artifact-id   (-> edge :to :id)
        qualified     (last artifact-id)            ; ["ns/name" :clojure ...]
        [ns-str sym]  (qualified-name->parts qualified)
        proj-kind     (:projection-kind edge)
        canvas-kind   (infer-canvas-kind primitive proj-kind)
        expected-path (ns->file-path ns-str)
        module-coord  (module-coord-from-stable-id primitive-id)
        label         (:label primitive)]
    {:check     :inspect.drift/missing-implementation
     :severity  :warning
     :message   (str "Canvas declares "
                     (when canvas-kind (str (name canvas-kind) " "))
                     (or label primitive-id)
                     (when module-coord (str " at " module-coord))
                     "; no matching code-side artifact at "
                     (or qualified "(unknown)") ".")
     :offenders [{:stable-id           primitive-id
                  :expected-code-path  expected-path
                  :expected-symbol     sym
                  :canvas-kind         canvas-kind}]
     :detail    {:canvas-side-id     primitive-id
                 :code-side-expected qualified
                 :projection-kind    proj-kind}}))

(defn check-missing-implementation
  "Walk the model's projection edges; emit one finding per `:validity
   :absent` edge. Uniform across all projected entity kinds — functions,
   events, invariants, rules, getters, checkers, and types — because
   Phase 6 Sprint 2 unified the analyzer's projection path."
  [model]
  (->> (:edges model)
       (filter projects-edge?)
       (filter absent?)
       (mapv #(absent-edge->finding model %))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn ^:export check
  "Run drift checks against the model. Returns a vector of finding maps.
   Returns [] when every canvas declaration has a matching code-side
   counterpart. Each finding carries :severity :warning — drift is
   fact-of-discrepancy but resolution is judgment.

   Phase 6 ships the missing-implementation umbrella check; shape-drift
   on records lands in Task 7."
  [model]
  (into [] (check-missing-implementation model)))
