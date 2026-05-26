(ns canvas.project-layer.registry
  "Canvas port of project_layer/registry.allium + registry.boundary.

   Coverage:
     - record IdiomRoute      → construction/record (3 optional String fields)
     - record IdiomEntry      → construction/record (route + body)
     - record Registry        → construction/record (3 fields; see TODO below)
     - invariant PureRegistration    → vocab.behavioral/invariant
     - invariant IdentityRegistry    → vocab.behavioral/invariant
     - fn make_registry        → construction/function
     - fn with_root_prefix     → construction/function
     - fn with_type_override   → construction/function
     - fn with_idiom           → construction/function
     - exports IdiomRoute IdiomEntry Registry → construction/exports

   TODO: Registry.type_overrides is Map<String, Any> — no map-of combinator
         exists. Approximated as :Map with inline comment.

   Shape grammar discipline:
     - IdiomRoute fields: all String? → (optional :String)
     - Registry.idioms: List<IdiomEntry> → (list-of :IdiomEntry)"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "project_layer.registry"

      ;; Value types from registry.allium

      (record "IdiomRoute"
        "Predicate map selecting which primitives an idiom applies to.
         All keys optional; an empty route matches every primitive."
        (field primitive_kind  (optional :String))
        (field projection_kind (optional :String))
        (field address_pattern (optional :String)))

      (record "IdiomEntry"
        "One project-layer idiom: a route + a body payload. The body shape
         is open and interpreted by the consuming analyzer or projector."
        (field route :IdiomRoute)
        (field body  :Any))

      (record "Registry"
        "Per-project projection-input bundle. Consumed by the target-language
         analyzers and projectors when assembling canonical addresses, rendering
         types, and applying idioms."
        (field root_prefix    :String)
        ;; TODO: map-of :String :Any — no map-of combinator exists yet.
        ;;       type_overrides is Map<String, Any>; approximated as :Map.
        (field type_overrides :Map)
        (field idioms         (list-of :IdiomEntry)))

      ;; Invariants from registry.allium

      (invariant "PureRegistration"
        "The with_* registrations are pure value updates over the registry.
         They never read or mutate state outside the supplied registry value,
         and produce a new registry value without side effects."
        (holds-that "pure-registration"))

      (invariant "IdentityRegistry"
        "make_registry produces the identity registry: empty root_prefix,
         empty type_overrides, empty idioms. Composing zero registrations on
         top of the identity registry yields the identity registry unchanged."
        (holds-that "identity-registry"))

      ;; Public functions from registry.boundary

      (function "make_registry"
        "Construct the identity registry: empty root_prefix, empty
         type_overrides, empty idioms. Suitable for the fukan-on-fukan
         self-referential case."
        (takes [])
        (gives :Registry))

      (function "with_root_prefix"
        "Set the target-language namespace root prefix on the registry."
        (takes [registry :Registry
                prefix   :String])
        (gives :Registry))

      (function "with_type_override"
        "Register a per-Scalar-name type rendering. The Analyzer's
         type-translation consults the registry before falling back to
         the substrate default."
        (takes [registry    :Registry
                scalar_name :String
                rendering   :Any])
        (gives :Registry))

      (function "with_idiom"
        "Append an idiom entry to the registry."
        (takes [registry :Registry
                entry    :IdiomEntry])
        (gives :Registry))

      (exports Registry IdiomEntry IdiomRoute))))
