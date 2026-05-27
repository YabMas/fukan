(ns fukan.canvas.inspect.drift
  "Drift detection — trust-tier feedback signal across canvas ↔ code.

   Canvas declares design intent; src/ holds implementation. Over time the
   two diverge — a canvas function declared but never written, an invariant
   declared with no fn enforcing it, a rule with no realising code, a
   record whose fields evolved on one side but not the other. The Clojure
   Target Analyzer already tags each projection edge with
   `:validity {:valid | :absent}`; this helper reads those edges and turns
   `:absent` into structured, decision-ready findings. Task 7 (this file)
   adds a SECOND check that compares the canvas-declared field shape of a
   record against the code-side defrecord/Malli schema field shape.

   Every finding is `:severity :warning` — drift is fact-of-discrepancy,
   resolution is judgment. Both sides of the discrepancy are named so the
   LLM can weigh whether the canvas should move (drop the declaration) or
   the code should move (add the implementation).

   Phase 6 Task 6 shipped the missing-implementation umbrella check (one
   check fn handles functions, events, invariants, rules, getters,
   checkers uniformly thanks to Sprint 2's unified projection). Task 7
   adds `check-shape-drift` for records.

   Public API:

     (check model)
     (check model canvas-db)
        Run all drift checks against the loaded Model. The two-arg form
        passes a canvas Datascript db for shape-drift comparison; the
        one-arg form skips shape-drift (no canvas-side fields available).
        Returns a vector of finding maps; [] when every canvas declaration
        has a matching code-side counterpart."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datascript.core :as d]))

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
;; Check 2 — Shape drift on records
;; ---------------------------------------------------------------------------
;;
;; Compares the canvas-declared field shape of a Type record (`:type/fields`
;; on the canvas db) against the code-side field shape carried on a
;; `Code.DataStructure` artifact (`:fields` in its `:sub` map, populated by
;; the analyzer when reading a Malli `[:map …]` schema or a defrecord field
;; list).
;;
;; Three drift sub-shapes per record:
;;   - only-in-canvas — fields the canvas declares that code doesn't have
;;   - only-in-code   — fields code has that the canvas doesn't declare
;;   - type-mismatch  — fields on both sides whose types differ post-alias-
;;                       normalisation
;;
;; The alias table reconciles PascalCase canvas type names (`:Integer`,
;; `:String`) with lowercase Malli-style names (`:int`, `:string`). It
;; covers the small set of scalars that actually appear in fukan's canvas;
;; unmapped types pass through unchanged. The table is intentionally tight —
;; an over-broad mapping would silently mask real type mismatches.

(def ^:private canvas->malli-aliases
  "Bidirectional alias table reconciling canvas (PascalCase) type names with
   Malli-style (lowercase) type names. Used by `normalize-type-keyword` to
   put both sides on common ground before shape comparison. Keep tight —
   prefer reporting a type-mismatch finding to silently masking one via an
   over-broad alias."
  {:Integer  :int
   :String   :string
   :Boolean  :boolean
   :Float    :float
   :Double   :double
   :Long     :long
   :Keyword  :keyword
   :Symbol   :symbol
   :Map      :map
   :Vector   :vector
   :Set      :set
   :Any      :any
   ;; :Unit in canvas means 'value not meaningful' — Malli has no direct
   ;; counterpart. Treat as :any so a canvas :Unit/code :any pair compares
   ;; clean.
   :Unit     :any})

(defn- normalize-type-keyword
  "Normalise a type keyword for shape comparison. Canvas-side PascalCase
   keywords are downcased via the alias table; code-side keywords pass
   through unchanged (they are already in the canonical lowercase form).
   Unknown keywords pass through verbatim — drift findings then surface
   the un-normalised mismatch."
  [tk]
  (or (canvas->malli-aliases tk) tk))

(defn- canvas-record-fields
  "Query the canvas-db for every Type entity carrying `:type/fields`. Returns
   a map `{stable-id {field-name-kw type-name-kw …}}` keeping the canvas-
   declared type name verbatim (no normalisation). If a field appears with
   multiple declared types on the canvas side (e.g. a sum-of), the first
   one wins; shape drift won't fan out per-variant.

   The canvas substrate stores fields as a set of [name type] tuples; we
   collapse per-field by taking one entry per field-name. The canvas db
   stores entities under a random UUID `:entity/id`; the projection layer
   converts UUID → stable-id-string of the form `<module>/type/<Name>`. We
   reconstruct that stable-id here by joining Type entities against their
   owning Module."
  [canvas-db]
  (let [tuples (d/q '[:find ?mod-name ?type-name ?field-tuple
                       :where [?m :entity/type :Module]
                              [?m :entity/name ?mod-name]
                              [?m :module/child ?e]
                              [?e :entity/type :Type]
                              [?e :entity/name ?type-name]
                              [?e :type/fields ?field-tuple]]
                     canvas-db)]
    (reduce (fn [acc [mod-name type-name [fname ftype]]]
              (let [stable-id (str mod-name "/type/" type-name)]
                (update acc stable-id (fn [existing]
                                        (assoc (or existing {}) fname ftype)))))
            {}
            tuples)))

(defn- code-record-fields-by-primitive
  "Walk projects edges from Type primitives to Code.DataStructure artifacts
   (validity :valid only), pull the artifact's :fields, return a map
   `{primitive-stable-id {field-name-kw type-name-kw …}}` keeping the
   code-side type names verbatim."
  [model]
  (let [edges (->> (:edges model)
                   (filter projects-edge?)
                   (filter (fn [e] (= :valid (:validity e))))
                   (filter (fn [e] (= :projection-kind/schema
                                      (:projection-kind e)))))]
    (reduce (fn [acc edge]
              (let [prim-id (-> edge :from :id)
                    art-id  (-> edge :to :id)
                    art     (get-in model [:artifacts art-id])
                    fields  (get-in art [:sub :fields])]
                (if (seq fields)
                  (assoc acc prim-id (into {} fields))
                  acc)))
            {}
            edges)))

(defn- artifact-id-for-primitive
  "Find the Code.DataStructure artifact id projected from `primitive-id`.
   Returns nil if no such edge exists."
  [model primitive-id]
  (some (fn [e]
          (when (and (projects-edge? e)
                     (= primitive-id (-> e :from :id))
                     (= :projection-kind/schema (:projection-kind e)))
            (-> e :to :id)))
        (:edges model)))

(defn- compute-delta
  "Compare canvas fields vs code fields. Inputs carry the original
   (unnormalised) type keywords from each side; comparison normalises
   through `canvas->malli-aliases` so `:Integer ↔ :int`, `:String ↔
   :string`, etc. collapse to clean. The returned delta carries the
   ORIGINAL (unnormalised) values so the finding shows what each side
   literally declared. Returns `{:only-in-canvas {} :only-in-code {}
   :type-mismatch {}}`."
  [canvas-fields code-fields]
  (let [canvas-names (set (keys canvas-fields))
        code-names   (set (keys code-fields))
        only-canvas  (set/difference canvas-names code-names)
        only-code    (set/difference code-names canvas-names)
        common       (set/intersection canvas-names code-names)
        mismatch     (reduce (fn [acc fname]
                                (let [ct (canvas-fields fname)
                                      kt (code-fields fname)]
                                  (if (= (normalize-type-keyword ct)
                                         (normalize-type-keyword kt))
                                    acc
                                    (assoc acc fname {:canvas ct :code kt}))))
                              {}
                              common)]
    {:only-in-canvas (select-keys canvas-fields only-canvas)
     :only-in-code   (select-keys code-fields only-code)
     :type-mismatch  mismatch}))

(defn- delta-empty?
  "True when a delta carries no drift in any of its three sub-shapes."
  [{:keys [only-in-canvas only-in-code type-mismatch]}]
  (and (empty? only-in-canvas)
       (empty? only-in-code)
       (empty? type-mismatch)))

(defn- shape-drift-finding
  "Build one shape-drift finding for a record whose canvas + code field
   shapes diverge. `prim-id` names the canvas side; `qualified` is the
   code-side qualified-name (ns/Name); `canvas-fields` and `code-fields`
   are the pre-normalisation maps; `delta` is the computed diff."
  [prim-id qualified canvas-fields code-fields delta]
  (let [module-coord (module-coord-from-stable-id prim-id)
        [ns-str sym] (qualified-name->parts qualified)
        path         (ns->file-path ns-str)]
    {:check     :inspect.drift/shape-drift-on-record
     :severity  :warning
     :message   (str "Canvas record " (or sym prim-id)
                     (when module-coord (str " (" module-coord ")"))
                     " has fields " (pr-str canvas-fields)
                     "; code-side at " (or path "(unknown)")
                     " has " (pr-str code-fields) ".")
     :offenders [{:stable-id       prim-id
                  :code-side-path  path
                  :canvas-fields   canvas-fields
                  :code-fields     code-fields
                  :delta           delta}]
     :detail    {:canvas-side-id     prim-id
                 :code-side-expected qualified}}))

(defn check-shape-drift
  "Compare every canvas-declared record's `:type/fields` against the
   matching `Code.DataStructure` artifact's `:fields`. Emit one finding
   per record whose field shape (names + normalised types) differs.

   Requires `canvas-db` — the canvas-side Datascript db carrying
   `:type/fields` tuples. Records without code-side field data (no
   `:fields` on the artifact) are silently skipped — comparison isn't
   possible.

   Type-name normalisation runs both sides through
   `canvas->malli-aliases` so `:Integer ↔ :int`, `:String ↔ :string`,
   `:Unit ↔ :any`, etc. compare clean."
  [model canvas-db]
  (let [canvas-fields-by-id (canvas-record-fields canvas-db)
        code-fields-by-prim (code-record-fields-by-primitive model)]
    (->> canvas-fields-by-id
         (keep (fn [[prim-id canvas-fields]]
                 (when-let [code-fields (get code-fields-by-prim prim-id)]
                   (let [delta (compute-delta canvas-fields code-fields)]
                     (when-not (delta-empty? delta)
                       (let [art-id (artifact-id-for-primitive model prim-id)
                             qname  (last art-id)]
                         (shape-drift-finding prim-id qname
                                              canvas-fields code-fields
                                              delta)))))))
         vec)))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn ^:export check
  "Run drift checks against the model. Returns a vector of finding maps.
   Returns [] when every canvas declaration has a matching code-side
   counterpart. Each finding carries :severity :warning — drift is
   fact-of-discrepancy but resolution is judgment.

   One-arg form runs the missing-implementation check only. Two-arg form
   additionally runs shape-drift comparing canvas-declared record field
   shapes against the analyzer-extracted code-side field shapes."
  ([model]
   (vec (check-missing-implementation model)))
  ([model canvas-db]
   (into (vec (check-missing-implementation model))
         (check-shape-drift model canvas-db))))
