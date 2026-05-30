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
            [datascript.core :as d]
            [fukan.canvas.core.substrate.store :as store]))

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
    :projection-kind/operation     :function
    :projection-kind/rule          :rule
    :projection-kind/invariant     :invariant
    :projection-kind/property-test :invariant
    :projection-kind/schema        :type
    :projection-kind/test          :test
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

(defn- ns->test-file-path
  "Convert a Clojure namespace to a conventional test file path under
   test/. Mirrors `ns->file-path` but rooted at `test/`. Used by Phase 8
   Sprint 5's invariant→property-test projection to derive the expected
   path of `defspec` artifacts: a canvas invariant projects to
   `test/fukan/<module>_test.clj` rather than `src/<module>.clj`."
  [ns-string]
  (when (string? ns-string)
    (str "test/"
         (-> ns-string
             (str/replace "." "/")
             (str/replace "-" "_"))
         ".clj")))

(defn- expected-path-for
  "Derive the expected source-file path for a projects-edge target.
   For `:projection-kind/property-test` edges (invariant → defspec
   under test/), the path is `test/<ns-as-path>.clj`. All other
   projection-kinds resolve under `src/`. Sprint 5 added the test-side
   branch; pre-Sprint-5 callers always saw `src/`."
  [projection-kind ns-str]
  (case projection-kind
    :projection-kind/property-test (ns->test-file-path ns-str)
    (ns->file-path ns-str)))

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
   artifact id on the `:to` side.

   The expected-code-path branches on the edge's `:projection-kind`:
   property-test projections resolve under `test/`; all other kinds
   resolve under `src/`. The finding's offender carries the
   `:projection-kind` so Layer B (drift-close) can render kind-aware
   neighbor sections."
  [model edge]
  (let [primitive-id  (-> edge :from :id)
        primitive     (get-in model [:primitives primitive-id])
        artifact-id   (-> edge :to :id)
        qualified     (last artifact-id)            ; ["ns/name" :clojure ...]
        [ns-str sym]  (qualified-name->parts qualified)
        proj-kind     (:projection-kind edge)
        canvas-kind   (infer-canvas-kind primitive proj-kind)
        expected-path (expected-path-for proj-kind ns-str)
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
                  :canvas-kind         canvas-kind
                  :projection-kind     proj-kind}]
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

;; ---------------------------------------------------------------------------
;; Canonical compound-shape representation
;;
;; Phase 7 Task 3 — recurse into compound shapes (set-of, list-of, map-of,
;; optional, sum-of, tuple-of, record-of) and compare structurally.
;;
;; Both sides flow through a single canonical form before comparison so
;; canvas `(set-of :NodeId)` and Malli `[:set :NodeId]` collapse to the
;; same value. Leaves continue to normalise through `canvas->malli-aliases`.
;;
;; Canonical form (mirrors `fukan.canvas.core.shape/parse` output):
;;   {:kind :leaf :name :string}                       atomic + refs (collapsed)
;;   {:kind :optional :inner <shape>}
;;   {:kind :list :elem <shape>}                       list-of / sequential / vector
;;   {:kind :set :elem <shape>}
;;   {:kind :map :key <shape> :val <shape>}
;;   {:kind :sum :variants [<shape> ...]}
;;   {:kind :tuple :elems [<shape> ...]}
;;   {:kind :record :fields [[name <shape>] ...]}
;;
;; Interpretive calls:
;;   * `:atomic` and `:ref` from canvas-side parse collapse to a single
;;     `:leaf` form — the comparator can't tell apart "type named :NodeId"
;;     from "ref to module-scoped :NodeId", and both sides only carry the
;;     leaf-keyword anyway.
;;   * Malli `[:sequential T]`, `[:vector T]`, AND `[:list T]` all map to
;;     canonical `:list` — the canvas vocabulary distinguishes only "list
;;     of" vs "set of" vs "map of"; concrete ordered-collection types in
;;     Malli are an implementation detail below that distinction.
;;   * Malli `[:maybe T]` and canvas `(optional T)` are equivalent.
;;   * A bare `:set` / `:sequential` / `:vector` head keyword on the code
;;     side (the head-only output of today's `target/clojure/source`
;;     analyzer) is recognised as a compound head with an *unknown*
;;     element type and represented as `{:kind :list/:set :elem
;;     {:kind :leaf :name :any}}`. A canvas `(list-of :T)` will type-
;;     mismatch against that unless `:T` itself reduces to `:any`. This is
;;     intentional: we don't want to silently mask differences when the
;;     analyzer hasn't yet extracted the inner type.

(def ^:private malli-list-heads
  "Malli vector heads that canonicalise to canvas `(list-of …)`. The canvas
   vocabulary has one ordered-collection compound; Malli has three near-
   synonyms (sequential / vector / list). Treating them as equivalent is
   the smallest interpretive call that avoids spurious shape-drift findings
   between e.g. `(list-of :CytoscapeNode)` and `[:sequential CytoscapeNode]`."
  #{:sequential :vector :list})

(declare ^:private to-canonical)

(defn- leaf
  "Build a canonical leaf for a type keyword, normalising via the alias
   table so :Integer ↔ :int etc. compare clean."
  [tk]
  {:kind :leaf :name (normalize-type-keyword tk)})

(defn- canvas-shape->canonical
  "Normalise a canvas-side parsed shape map (the output of
   `fukan.canvas.core.shape/parse`, reconstructed from the reified
   `:node/shape` tree) to the comparator's canonical form."
  [shape]
  (case (:kind shape)
    :atomic   (leaf (:name shape))
    :ref      (leaf (:target shape))
    :optional {:kind :optional :inner (to-canonical (:inner shape))}
    :list     {:kind :list :elem (to-canonical (:elem shape))}
    :set      {:kind :set :elem (to-canonical (:elem shape))}
    :map      {:kind :map
               :key (to-canonical (:key shape))
               :val (to-canonical (:val shape))}
    :sum      {:kind :sum :variants (mapv to-canonical (:variants shape))}
    :tuple    {:kind :tuple :elems (mapv to-canonical (:elems shape))}
    :record   {:kind :record
               :fields (mapv (fn [[n s]] [n (to-canonical s)]) (:fields shape))}
    ;; Unknown / future canvas shape kinds — fall back to a leaf so the
    ;; comparator can still produce a sane mismatch finding.
    (leaf (or (:name shape) (:target shape) :unknown))))

(def ^:private unknown-leaf
  "Sentinel leaf used when the inner type of a compound is structurally
   absent on the code side (today's analyzer flattens `[:set :NodeId]` to
   `:set`). `shape=?` treats this as a wildcard so a head-only code-side
   compound (`:set`) compares equal to a canvas-side compound of the same
   outer kind (`(set-of :NodeId)`) — the analyzer didn't extract enough
   information to disagree.

   The wildcard is intentionally narrow: it only matches when the OUTER
   compound kinds agree on both sides. Different outer kinds (set vs
   vector) still produce a finding. A bare scalar on the code side does
   NOT use this sentinel — `:int` vs canvas `(optional :Integer)` remains
   a genuine type-mismatch."
  {:kind :leaf :name ::unknown})

(defn- code-shape->canonical
  "Normalise a code-side shape descriptor to the comparator's canonical
   form. Code-side descriptors arrive in two flavours:

   * Bare keyword — today's analyzer extraction collapses Malli compounds
     to their head keyword (e.g. `[:set :NodeId]` → `:set`). The compound
     head is recognised and represented with `unknown-leaf` for its
     element type; `shape=?` treats `unknown-leaf` as a wildcard so the
     resulting compound matches any canvas-side compound of the same
     outer kind. Genuine outer-kind divergences (set vs vector) still
     surface.
   * Vector — direct Malli-style expressions (e.g. `[:set :NodeId]`,
     `[:map-of :string :int]`). Recursed structurally."
  [shape]
  (cond
    (keyword? shape)
    (cond
      (= :any shape)                    (leaf :any)
      (contains? malli-list-heads shape) {:kind :list :elem unknown-leaf}
      (= :set shape)                    {:kind :set :elem unknown-leaf}
      (= :map-of shape)                 {:kind :map :key unknown-leaf :val unknown-leaf}
      (= :map shape)                    (leaf :map)
      (= :maybe shape)                  {:kind :optional :inner unknown-leaf}
      (= :or shape)                     {:kind :sum :variants []}
      (= :tuple shape)                  {:kind :tuple :elems []}
      :else                             (leaf shape))

    (vector? shape)
    (let [head (first shape)
          ;; Malli entries may carry an options map immediately after the
          ;; head; skip it so [:vector {:min 1} :T] reads as :vector with
          ;; elem :T.
          rest-args (let [r (rest shape)]
                      (if (and (seq r) (map? (first r))) (rest r) r))]
      (cond
        (contains? malli-list-heads head)
        {:kind :list :elem (to-canonical (first rest-args))}

        (= :set head)
        {:kind :set :elem (to-canonical (first rest-args))}

        (= :map-of head)
        {:kind :map
         :key (to-canonical (first rest-args))
         :val (to-canonical (second rest-args))}

        (= :maybe head)
        {:kind :optional :inner (to-canonical (first rest-args))}

        (= :or head)
        {:kind :sum :variants (mapv to-canonical rest-args)}

        (= :tuple head)
        {:kind :tuple :elems (mapv to-canonical rest-args)}

        (= :map head)
        ;; Inline Malli :map — entries are [name (opts?) type] vectors.
        {:kind :record
         :fields (mapv (fn [entry]
                         (let [n     (first entry)
                               tail  (rest entry)
                               tail  (if (and (seq tail) (map? (first tail)))
                                       (rest tail)
                                       tail)]
                           [n (to-canonical (first tail))]))
                       rest-args)}

        :else
        ;; Unknown vector head — first arg as leaf is the historical
        ;; analyzer behaviour; keep it for non-breaking back-compat.
        (leaf head)))

    (map? shape)
    ;; Already-canonical shape (e.g. canvas pre-parsed). Pass through.
    shape

    :else
    (leaf :any)))

(defn- to-canonical
  "Dispatch to canvas- or code-side normalisation based on the shape's
   shape. Canvas shapes are edn maps carrying a :kind key from
   `shape/parse`; code-side shapes are bare keywords or Malli-style
   vectors."
  [shape]
  (cond
    (and (map? shape) (contains? shape :kind))
    (canvas-shape->canonical shape)

    :else
    (code-shape->canonical shape)))

(defn- unknown?
  [s]
  (and (map? s) (= :leaf (:kind s)) (= ::unknown (:name s))))

(defn- shape-equal?
  "Recursive structural equality on canonical shapes. `unknown-leaf`
   matches any shape (wildcard) so a head-only code-side compound
   collapses against a canvas-side compound of the same outer kind."
  [a b]
  (cond
    (or (unknown? a) (unknown? b))
    true

    (not= (:kind a) (:kind b))
    false

    :else
    (case (:kind a)
      :leaf     (= (:name a) (:name b))
      :optional (shape-equal? (:inner a) (:inner b))
      :list     (shape-equal? (:elem a) (:elem b))
      :set      (shape-equal? (:elem a) (:elem b))
      :map      (and (shape-equal? (:key a) (:key b))
                     (shape-equal? (:val a) (:val b)))
      :sum      (and (= (count (:variants a)) (count (:variants b)))
                     (every? identity
                             (map shape-equal? (:variants a) (:variants b))))
      :tuple    (and (= (count (:elems a)) (count (:elems b)))
                     (every? identity
                             (map shape-equal? (:elems a) (:elems b))))
      :record   (and (= (mapv first (:fields a)) (mapv first (:fields b)))
                     (every? identity
                             (map (fn [[_ sa] [_ sb]] (shape-equal? sa sb))
                                  (:fields a) (:fields b))))
      ;; Fallback — compare raw.
      (= a b))))

(defn- shape=?
  "Structural equality on raw (mixed canvas / code) shape values. Each
   side flows through `to-canonical` first; the alias table collapses
   leaf scalars (`:Integer ↔ :int`) and structural recursion checks
   compound shapes."
  [a b]
  (shape-equal? (to-canonical a) (to-canonical b)))

(defn- canvas-record-fields
  "Query the canvas-db for every record Type entity. Returns a map
   `{stable-id {field-name-kw <shape> …}}`. The shape value is the per-field
   parsed compound shape reconstructed from the reified `:node/shape` tree
   when present; otherwise it falls back to the leaf type-keyword from
   `:type/fields`.

   If a field appears only via the leaf fallback (no reified shape), the
   first leaf wins; shape drift won't fan out per-variant. With the reified
   `:node/shape` present the full compound is preserved instead.

   The canvas db stores entities under a random UUID `:entity/id`; the
   projection layer converts UUID → stable-id-string of the form
   `<module>/type/<Name>`. We reconstruct that stable-id here by joining
   Type entities against their owning Module."
  [canvas-db]
  (let [leaf-tuples (d/q '[:find ?mod-name ?type-name ?field-tuple
                            :where [?m :entity/type :Module]
                                   [?m :entity/name ?mod-name]
                                   [?m :module/child ?e]
                                   [?e :entity/type :Type]
                                   [?e :entity/name ?type-name]
                                   [?e :type/fields ?field-tuple]]
                          canvas-db)
        shape-roots (d/q '[:find ?mod-name ?type-name ?sh
                            :where [?m :entity/type :Module]
                                   [?m :entity/name ?mod-name]
                                   [?m :module/child ?e]
                                   [?e :entity/type :Type]
                                   [?e :entity/name ?type-name]
                                   [?e :node/shape ?sh]]
                          canvas-db)
        ;; Rich shapes first — they fully describe the compound; leaves fill
        ;; in fields that lack a richer entry (e.g. test fixtures that go
        ;; through the canvas-db directly).
        with-leaves (reduce (fn [acc [mod-name type-name [fname ftype]]]
                              (let [stable-id (str mod-name "/type/" type-name)]
                                (update acc stable-id (fn [existing]
                                                        (let [cur (or existing {})]
                                                          (if (contains? cur fname)
                                                            cur
                                                            (assoc cur fname ftype)))))))
                            {}
                            leaf-tuples)]
    ;; Rich per-field shapes read back from the reified :node/shape tree.
    (reduce (fn [acc [mod-name type-name sh]]
              (let [stable-id (str mod-name "/type/" type-name)
                    fields    (:fields (store/read-reified-shape canvas-db sh))]
                (update acc stable-id
                        (fn [existing]
                          (reduce (fn [m [fname pshape]]
                                    (assoc m (keyword fname) pshape))
                                  (or existing {})
                                  fields)))))
            with-leaves
            shape-roots)))

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
   (unnormalised) shape values from each side — these may be bare
   type keywords (leaf scalars on either side, head-only Malli compound
   from the analyzer) or full compound shapes (canvas parsed shape from
   `:type/field-shapes`, Malli vector on the code side). Comparison
   normalises both sides through `to-canonical` + the leaf alias table
   and tests structural equality with `shape=?`. The returned delta
   carries the ORIGINAL (unnormalised) values so the finding shows what
   each side literally declared. Returns
   `{:only-in-canvas {} :only-in-code {} :type-mismatch {}}`."
  [canvas-fields code-fields]
  (let [canvas-names (set (keys canvas-fields))
        code-names   (set (keys code-fields))
        only-canvas  (set/difference canvas-names code-names)
        only-code    (set/difference code-names canvas-names)
        common       (set/intersection canvas-names code-names)
        mismatch     (reduce (fn [acc fname]
                                (let [ct (canvas-fields fname)
                                      kt (code-fields fname)]
                                  (if (shape=? ct kt)
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
;; Scoped filtering via :module-coord (Phase 7 Sprint 2 Task 5)
;;
;; Prefix-matching is dot-segment-aware: `:module-coord "mod-a"` matches
;; offenders whose module-coord is `mod-a` or `mod-a.<anything>`, but not
;; `mod-ab`. This mirrors how Clojure-style module names compose — a parent
;; module is a proper dot-prefix of its children. String-prefix would
;; over-match (mod-a → mod-ab) and erode the filter's value as a verification
;; tool after dispatching a fix to a single module.

(defn- finding-module-coord
  "Pull the offender :stable-id from a finding and return its leading
   module-coord segment (everything before the first '/'). Returns nil if
   the finding has no offenders or no stable-id — such findings are kept
   under any scope (better to over-report than to silently drop)."
  [finding]
  (when-let [stable-id (-> finding :offenders first :stable-id)]
    (module-coord-from-stable-id stable-id)))

(defn- in-scope?
  "True when `coord` is `scope` exactly, or descends from it via a dot
   segment boundary. nil scope ⇒ everything in scope. nil coord ⇒ in scope
   (orphan findings without a stable-id are preserved)."
  [coord scope]
  (or (nil? scope)
      (nil? coord)
      (= coord scope)
      (str/starts-with? coord (str scope "."))))

(defn- apply-scope
  "Narrow a finding vector to those whose offender module-coord matches
   `scope`. Pure post-walk on the structured finding output; no schema or
   substrate dependency. nil scope is a no-op."
  [findings scope]
  (if (nil? scope)
    findings
    (filterv #(in-scope? (finding-module-coord %) scope) findings)))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn ^:export check
  "Run drift checks against the model. Returns a vector of finding maps.
   Returns [] when every canvas declaration has a matching code-side
   counterpart. Each finding carries :severity :warning — drift is
   fact-of-discrepancy but resolution is judgment.

   Arities:
     (check model)
     (check model opts)
     (check model canvas-db)
     (check model canvas-db opts)

   `opts` is a map; supported keys:
     :module-coord <string>  Narrow findings to a module subtree. Matching
                              is dot-segment-aware: \"mod-a\" matches
                              `mod-a` and `mod-a.sub.*` but NOT `mod-ab`.
                              Filtering is a post-walk on offender
                              :stable-id and never touches the analyzer or
                              substrate.

   The one-arg / model-only forms run the missing-implementation check
   only. The forms passing `canvas-db` additionally run shape-drift on
   records, comparing canvas-declared field shapes against the
   analyzer-extracted code-side fields."
  ([model] (check model nil nil))
  ([model canvas-db-or-opts]
   ;; Discriminate canvas-db vs opts-map. A Datascript db carries :eavt /
   ;; :aevt / :avet indexes; an opts map does not. Either may be nil.
   (if (and (map? canvas-db-or-opts)
            (contains? canvas-db-or-opts :eavt))
     (check model canvas-db-or-opts nil)
     (check model nil canvas-db-or-opts)))
  ([model canvas-db opts]
   (let [{:keys [module-coord]} opts
         findings (cond-> (vec (check-missing-implementation model))
                    canvas-db (into (check-shape-drift model canvas-db)))]
     (apply-scope findings module-coord))))
