(ns canvas.model.build
  "Canvas port of model/build.allium + build.boundary.

   Coverage:
     - 5 guarantees from build.allium → vocab.behavioral/invariant each:
         PureConstruction, UniquePrimitiveIds, EndpointResolution,
         MultiEdgeIdentity, UniqueArtifactIdentity
     - fn empty_model            → construction/function
     - fn get_primitive          → construction/function
     - fn add_primitive          → construction/function
     - fn add_edge               → construction/function
     - fn edges_by_kind          → construction/function
     - fn edges_from             → construction/function
     - fn edges_to               → construction/function
     - fn add_tag_definition     → construction/function
     - fn add_tag_application    → construction/function
     - fn add_predicate          → construction/function
     - fn add_renderer           → construction/function
     - fn get_artifact           → construction/function
     - fn add_artifact           → construction/function

   Notes:
     - This is the Model construction API; the Model value shape itself
       lives in canvas.model.spec.
     - Build pipeline orchestration lives in canvas.model.pipeline."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "model.build"

      ;; Guarantees from build.allium — mapped to invariant lifts

      (invariant "PureConstruction"
        "All construction helpers are pure value updates over the supplied
         Model. They never read or mutate state outside the supplied arguments,
         and they produce a new Model value without side effects."
        (holds-that "construction-helpers-are-pure-value-updates"))

      (invariant "UniquePrimitiveIds"
        "add_primitive raises if its primitive's id collides with an existing
         primitive. Two primitives never share an id within a single Model."
        (holds-that "add-primitive-raises-on-duplicate-id"))

      (invariant "EndpointResolution"
        "add_edge validates that the edge's :from and :to endpoints resolve
         against the current Model: PrimitiveRef -> primitive must be present;
         ArtifactRef -> artifact must be registered; SubstrateAddress ->
         container must be present and field-slot paths must point at a real
         Field. Unresolved endpoints raise."
        (holds-that "add-edge-validates-endpoint-resolution"))

      (invariant "MultiEdgeIdentity"
        "Two edges sharing the same identity (per §4.4: from, to, kind,
         per-relation identifying-metadata subset) collapse to one. add_edge
         is idempotent over edge identity: re-adding an existing edge returns
         the Model unchanged."
        (holds-that "add-edge-is-idempotent-over-edge-identity"))

      (invariant "UniqueArtifactIdentity"
        "add_artifact raises if its artifact's identity tuple collides with an
         already-registered artifact. The artifact registry is keyed by identity
         per §7.3."
        (holds-that "add-artifact-raises-on-duplicate-identity"))

      ;; Public functions from build.boundary

      (function "empty_model"
        "The empty Model: no primitives, no edges, no vocabulary, no artifacts.
         Suitable as the starting point for staged build."
        (takes [])
        (gives :model/Model))

      (function "get_primitive"
        "Look up a primitive by id. Returns nil when absent."
        (takes [model :model/Model
                id    :String])
        (gives (optional :Any)))

      (function "add_primitive"
        "Register a primitive under its :id. Raises on duplicate id."
        (takes [model     :model/Model
                primitive :Any])
        (gives :model/Model))

      (function "add_edge"
        "Append a kernel edge. Validates that both endpoints resolve to known
         primitives / artifacts / substrate addresses. Identity collisions
         (per edge-identity) are no-ops."
        (takes [model :model/Model
                edge  :Any])
        (gives :model/Model))

      (function "edges_by_kind"
        "All edges with the given relation kind."
        (takes [model :model/Model
                kind  :String])
        (gives (list-of :Any)))

      (function "edges_from"
        "All edges originating at endpoint."
        (takes [model    :model/Model
                endpoint :Any])
        (gives (list-of :Any)))

      (function "edges_to"
        "All edges terminating at endpoint."
        (takes [model    :model/Model
                endpoint :Any])
        (gives (list-of :Any)))

      (function "add_tag_definition"
        "Append a TagDefinition to the vocabulary registry."
        (takes [model          :model/Model
                tag_definition :Any])
        (gives :model/Model))

      (function "add_tag_application"
        "Append a TagApplication. Validates that primitive-targeted
         applications point at a known primitive."
        (takes [model           :model/Model
                tag_application :Any])
        (gives :model/Model))

      (function "add_predicate"
        "Append a PredicateRegistration to the vocabulary registry."
        (takes [model                  :model/Model
                predicate_registration :Any])
        (gives :model/Model))

      (function "add_renderer"
        "Append a RendererRegistration to the vocabulary registry."
        (takes [model                  :model/Model
                renderer_registration  :Any])
        (gives :model/Model))

      (function "get_artifact"
        "Look up a registered artifact by its identity tuple."
        (takes [model            :model/Model
                artifact_identity :Any])
        (gives (optional :Any)))

      (function "add_artifact"
        "Register an artifact under its identity tuple. Raises on
         duplicate identity."
        (takes [model    :model/Model
                artifact :Any])
        (gives :model/Model)))))
