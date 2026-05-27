(ns canvas.vocabulary.allium.tags
  "Canvas port of vocabulary/allium/tags.allium.

   Scope: The catalogue of Allium::* TagDefinitions shipped with the
   vocabulary, plus payload-shape commitments and the registration
   invariant the pipeline relies on.

   Coverage:
     - record AlliumTagCatalogue  (4 List<String> fields)
     - 3 invariants: CatalogueIsExhaustive, RegistrationIsIdempotent,
       TrustedTargetAssignment"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.tags"

      ;; AlliumTagCatalogue — documentation envelope for the Allium tag namespace.
      ;; Groups TagDefinitions by classification: primitive-role tags, edge-shading
      ;; tags, source-clause tags, and payload-bearing tags.
      (record "AlliumTagCatalogue"
        "Documentation envelope for the Allium tag namespace. The catalogue
         groups TagDefinitions by what they classify: primitive-role tags
         (Module, Entity, Value, Variant, ExternalEntity, Actor, Rule,
         Surface, Contract, Event, Call), edge-shading tags (Trigger,
         Provides, Exposes, Fulfils, Demands), and source-clause tags on
         substrate addresses (Invariant, Requires, Let, Ensures,
         ContractInvariant, SurfaceGuarantee, Guidance).
         Payload-bearing tags: Actor, Surface, Trigger. All others payload-free."
        (field primitive_role_tags  (list-of :String))
        (field edge_shading_tags    (list-of :String))
        (field source_clause_tags   (list-of :String))
        (field payload_bearing_tags (list-of :String)))

      (invariant "CatalogueIsExhaustive"
        "Every Allium::* tag the analyzer applies must appear in the
         catalogue returned by allium_tag_definitions. The analyzer never
         applies an unregistered tag — a missing TagDefinition would fail
         payload-schema validation at build/add-tag-application time."
        (holds-that "every-applied-tag-in-catalogue"))

      (invariant "RegistrationIsIdempotent"
        "Registering the catalogue onto the same Model twice produces the
         same Model. TagDefinition identity is (namespace, name); a repeat
         registration with the same key is a no-op."
        (holds-that "tag-registration-idempotent"))

      (invariant "TrustedTargetAssignment"
        "Each TagDefinition declares the kernel target kind it applies to
         (primitive, container, edge, substrate, actor, rule, event,
         clause, operation). The analyzer is trusted to apply tags only
         to matching targets; the kernel enforces this at application
         time and rejects mismatches."
        (holds-that "tag-applied-only-to-declared-target-kind")))))
