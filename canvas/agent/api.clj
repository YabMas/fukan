(ns canvas.agent.api
  "Canvas port of agent/api.allium + api.boundary.

   Coverage:
     - value Envelope           → construction/record (4 fields)
     - value PrimitiveSummary   → construction/record (3 fields, optional label)
     - value RelationRow        → construction/record (5 fields)
     - value ArtifactSummary    → construction/record (6 fields)
     - value VocabularyEntry    → construction/record (2 fields)
     - value PrimitiveKindEntry → construction/record (4 fields)
     - value RelationKindEntry  → construction/record (2 fields)
     - value SchemaSummary      → construction/record (4 fields)
     - value DriftRow           → construction/record (6 fields)
     - value Neighborhood       → construction/record (4 fields)
     - value CoverageReport     → construction/record (8 fields)
     - value Endpoint           → construction/record (2 fields)
     - value Violation          → construction/record (5 fields)
     - value Primitive          → construction/record (3 fields)
     - fn q                     → construction/function (L0 raw Datalog)
     - fn primitives            → construction/function (L1 probe)
     - fn get_primitive         → construction/function (L1 probe)
     - fn relations             → construction/function (L1 probe)
     - fn vocabulary            → construction/function (L1 probe)
     - fn schema                → construction/function (L1 probe)
     - fn artifacts             → construction/function (L1 probe)
     - fn idioms                → construction/function (L1 probe)
     - fn constraints           → construction/function (L1 probe)
     - fn violations            → construction/function (L1 probe)
     - fn drift                 → construction/function (L2 view)
     - fn neighborhood          → construction/function (L2 view)
     - fn coverage              → construction/function (L2 view)
     - 5 invariants             → vocab.behavioral/invariant each
     - exports: all 14 value types

   Notes:
     - Cross-module type ref model.SourceLocation uses :model/SourceLocation.
     - q takes an opaque Datalog form and returns an opaque tuple set (:Value);
       it runs real Datascript d/q over the substrate db (see fukan.agent.api/q).
     - Envelope.rows is opaque (Value / Map) — typed as :Value / :Map."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "agent.api"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "Envelope"
        "Standard listing envelope returned by L1 probes. Pagination is
         always pre-applied; callers inspect :truncated? to learn whether
         additional offsets remain."
        (field rows      (list-of :Value))
        (field truncated :Boolean)
        (field total     :Integer)
        (field offset    :Integer))

      (record "PrimitiveSummary"
        "The narrowed view of a primitive that L1 listings return. Full
         primitive maps are reachable via get_primitive."
        (field id    :String)
        (field kind  :String)
        (field label (optional :String)))

      (record "RelationRow"
        "A relation edge as returned by the relations probe. Endpoints
         mirror the kernel edge structure."
        (field kind            :String)
        (field from            :Endpoint)
        (field to              :Endpoint)
        (field validity        (optional :String))
        (field projection_kind (optional :String)))

      (record "ArtifactSummary"
        "Narrowed artifact projection for L1 listings."
        (field id              :String)
        (field case            :String)
        (field sub_case        :String)
        (field language        :String)
        (field qualified_name  :String)
        (field public          (optional :Boolean))
        (field source_location (optional :model/SourceLocation)))

      (record "VocabularyEntry"
        "The vocabulary probe response. Splits primitive-kinds from
         relation-kinds."
        (field primitive_kinds (list-of :PrimitiveKindEntry))
        (field relation_kinds  (optional (list-of :RelationKindEntry))))

      (record "PrimitiveKindEntry"
        "One primitive-kind entry from the vocabulary probe."
        (field kind      :String)
        (field doc       (optional :String))
        (field face_role (optional :String))
        (field in_use    :Boolean))

      (record "RelationKindEntry"
        "One relation-kind entry from the vocabulary probe."
        (field kind   :String)
        (field in_use :Boolean))

      (record "SchemaSummary"
        "Empirical attribute and relation surface for a given primitive kind."
        (field kind       :String)
        (field attributes (list-of :String))
        (field relations  (list-of :String))
        (field count      :Integer))

      (record "DriftRow"
        "An absent projection edge joined with its source primitive."
        (field kind            :String)
        (field from            :Endpoint)
        (field to              :Endpoint)
        (field validity        :String)
        (field projection_kind :String)
        (field primitive       (optional :Primitive)))

      (record "Neighborhood"
        "One-hop view around a primitive — the primitive itself plus its
         outgoing/incoming edges and summaries of every directly-connected
         neighbor."
        (field primitive :Primitive)
        (field outgoing  (list-of :RelationRow))
        (field incoming  (list-of :RelationRow))
        (field neighbors (list-of :PrimitiveSummary)))

      (record "CoverageReport"
        "Spec→code coverage rollup for public Clojure functions."
        (field total_public_functions :Integer)
        (field covered                :Integer)
        (field expected_not_realised  :Integer)
        (field unprojected            :Integer)
        (field covered_ratio          :Number)
        (field unprojected_ratio      :Number)
        (field absent_edge_count      :Integer))

      (record "Endpoint"
        "Either a primitive id or an artifact id. :case is
         'endpoint/primitive' or 'endpoint/artifact'."
        (field case :String)
        (field id   :Value))

      (record "Violation"
        "Constraint violation report."
        (field severity  :String)
        (field phase     :String)
        (field sub_phase (optional :String))
        (field kind      :String)
        (field message   :String))

      (record "Primitive"
        "Full primitive map. Shape varies by :kind."
        (field id    :String)
        (field kind  :String)
        (field label (optional :String)))

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "ReadOnlyQueries"
        "Every fn in api.boundary observes the loaded Model without
         mutating it. Agents that want to change the Model edit source
         files and call system.refresh; api never writes."
        (holds-that "all-api-fns-are-read-only"))

      (invariant "EnvelopePagination"
        "L1 listings always return the standard Envelope shape with
         :rows :truncated? :total :offset. L2 views return pre-shaped
         values directly."
        (holds-that "l1-probes-return-envelope-shape"))

      (invariant "LayerDiscipline"
        "L0 (q) is the only entry point that runs arbitrary Datalog.
         L1 probes wrap the loaded Model with structured filters.
         L2 views may compose L1 calls. Higher layers never reach below L0."
        (holds-that "layer-discipline-l0-l1-l2"))

      (invariant "ModelLoadedPrecondition"
        "Probes that require a loaded Model raise :model-not-loaded when
         none is built. get_primitive, idioms, constraints, and violations
         tolerate the unloaded state and return nil / empty."
        (holds-that "model-loaded-precondition"))

      (invariant "FilterRejection"
        "Unknown filter keys raise :unknown-filter rather than silently
         ignoring them. The surface fails fast on caller typos."
        (holds-that "unknown-filter-raises-error"))

      ;; ── L0 Kernel ─────────────────────────────────────────────────────────

      (function "q"
        "Evaluate a Datascript Datalog query ([:find … :where …]) against the
         canvas substrate db. Full d/q dialect over the substrate vocabulary
         (:entity/type, :affordance/role, :entity/stable-id, :module/child,
         :uses, :edge/*, :artifact/*). Returns a set of result tuples."
        (takes [form :Value])
        (gives (set-of :Value)))

      ;; ── L1 Probes ─────────────────────────────────────────────────────────

      (function "primitives"
        "List Model primitive summaries. Optional :kind and :label filters,
         plus :limit / :offset pagination. Returns the standard envelope."
        (takes [filters (optional (map-of :String :Value))])
        (gives :Envelope))

      (function "get_primitive"
        "Return the full primitive map for an id, or null when absent."
        (takes [id :String])
        (gives (optional :Primitive)))

      (function "relations"
        "List Model relations. Filters: :kind, :from, :to, :validity,
         :projection-kind, plus :limit / :offset."
        (takes [filters (optional (map-of :String :Value))])
        (gives :Envelope))

      (function "vocabulary"
        "Surface kernel-declared primitive-kinds and relation-kinds, each
         tagged with :in-use? against the loaded Model."
        (takes [filters (optional (map-of :String :Value))])
        (gives :VocabularyEntry))

      (function "schema"
        "Empirically surface attribute keys and participating relation kinds
         for primitives of a given :kind."
        (takes [filters (optional (map-of :String :Value))])
        (gives :SchemaSummary))

      (function "artifacts"
        "List artifact summaries. Filters: :sub-case, :language, :public?,
         plus :limit / :offset."
        (takes [filters (optional (map-of :String :Value))])
        (gives :Envelope))

      (function "idioms"
        "Return the project-layer idiom entries from the loaded Model."
        (takes [])
        (gives (list-of :Value)))

      (function "constraints"
        "Return the project-layer constraint definitions from the loaded Model."
        (takes [])
        (gives (list-of :Value)))

      (function "violations"
        "Return current constraint violations. Optional :severity filter."
        (takes [filters (optional (map-of :String :Value))])
        (gives (list-of :Violation)))

      ;; ── L2 Views ──────────────────────────────────────────────────────────

      (function "drift"
        "Absent projections joined with their source primitive. Optional
         :projection-kind filter. Returns a plain vector, not an envelope."
        (takes [filters (optional (map-of :String :Value))])
        (gives (list-of :DriftRow)))

      (function "neighborhood"
        "Primitive plus its one-hop outgoing and incoming edges plus
         summaries of the directly-connected neighbors."
        (takes [id :String])
        (gives (optional :Neighborhood)))

      (function "coverage"
        "Spec→code projection coverage report for public functions only."
        (takes [])
        (gives :CoverageReport))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports Envelope PrimitiveSummary RelationRow ArtifactSummary
               VocabularyEntry PrimitiveKindEntry RelationKindEntry
               SchemaSummary DriftRow Neighborhood CoverageReport
               Endpoint Violation Primitive))))
