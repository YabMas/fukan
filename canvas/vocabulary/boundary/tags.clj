(ns canvas.vocabulary.boundary.tags
  "Canvas port of vocabulary/boundary/tags.allium.

   Scope: The catalogue of Boundary::* TagDefinitions shipped with the
   vocabulary, plus payload-shape commitments.

   Coverage:
     - record BoundaryTagCatalogue  (2 List<String> fields)
     - 2 invariants: CatalogueIsExhaustive, RegistrationIsIdempotent"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.boundary.tags"

      ;; BoundaryTagCatalogue — documentation envelope for the Boundary tag namespace.
      ;; Five tags: Function, Binding, ModuleApi (payload-bearing);
      ;; Subsystem, Exports (also payload-bearing, but distinguished by their
      ;; application targets). Separated into payload-bearing vs payload-free groups.
      (record "BoundaryTagCatalogue"
        "Documentation envelope for the Boundary tag namespace. Five tags:
           Function  — applied to an Operation primitive declared by a fn form.
           Binding   — applied to a triggers: Operation → Rule edge, with
                       optional :returns_expression payload.
           ModuleApi — applied to a module-Container when an exports: clause
                       is present, with :exported payload.
           Subsystem — applied to a composite Container declared by a subsystem
                       form, with :name payload.
           Exports   — applied to a subsystem-composite Container, with
                       :exported payload listing the subsystem's re-exports."
        (field payload_bearing_tags (list-of :String))
        (field payload_free_tags    (list-of :String)))

      (invariant "CatalogueIsExhaustive"
        "Every Boundary::* tag the analyzer applies must appear in the
         catalogue returned by boundary_tag_definitions. An unregistered
         tag would fail payload-schema validation at application time."
        (holds-that "every-applied-boundary-tag-in-catalogue"))

      (invariant "RegistrationIsIdempotent"
        "Registering the catalogue onto the same Model twice produces the
         same Model. TagDefinition identity is (namespace, name); repeats
         are no-ops."
        (holds-that "boundary-tag-registration-idempotent")))))
