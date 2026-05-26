(ns canvas.model.vocabulary
  "Canvas port of model/vocabulary.allium + vocabulary.boundary.

   Coverage:
     - 2 invariants from vocabulary.allium → vocab.behavioral/invariant each:
         HasTagFollowsAncestors, PrimitiveKindDescriptiveSurface
     - Vocabulary entry constructors:
         make_tag_definition, make_tag_application,
         make_predicate_registration, make_renderer_registration
     - Tag-presence implication (V9):
         has_tag_with_ancestors

   Notes:
     - TagDefinition / TagApplication / PredicateRegistration /
       RendererRegistration value types live in canvas.model.spec.
     - V9 inheritance guarantees (PayloadSchemaExtension, TagPresenceImplication,
       NoFieldOverride, NoMultiParent) live in canvas.model.spec.
     - Concrete predicate-language semantics are deferred (Plan 4).
     - Methodology vocabulary content lives in vocabulary/*.
     - PrimitiveKindDescriptiveSurface: this module carries one-sentence
       substrate framing per kernel primitive kind (primitive_kind_docs) and
       face-role grouping (face-host, face-interface, face-component,
       face-peer). Every PrimitiveKind value has both a doc and a face-role."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.vocabulary"

      ;; Invariants from vocabulary.allium

      (invariant "HasTagFollowsAncestors"
        "has_tag_with_ancestors realises spec.TagPresenceImplication for the
         :target/primitive case: a target carrying a child tag is treated, for
         predicate purposes, as carrying every ancestor in the parent_tag chain
         (transitively). Edge and substrate targets are not matched in Plan 1."
        (holds-that "has-tag-follows-ancestor-chain-transitively"))

      (invariant "PrimitiveKindDescriptiveSurface"
        "This module carries the one-sentence substrate framing per kernel
         primitive kind (primitive_kind_docs) and the face-role grouping
         (primitive_kind_face_roles: face-host, face-interface, face-component,
         face-peer). Every PrimitiveKind value declared in model/spec.allium
         has both a doc and a face-role; the agent (vocabulary) view assumes
         this coverage."
        (holds-that "every-primitive-kind-has-doc-and-face-role"))

      ;; Vocabulary entry constructors from vocabulary.boundary

      (function "make_tag_definition"
        "Construct a TagDefinition. Required: namespace, name, applies-to.
         Optional: payload-schema, parent-tag, relational."
        (takes [spec :Any])
        (gives :Any))

      (function "make_tag_application"
        "Construct a TagApplication. Required: tag, target. Optional: payload
         (defaults to empty map)."
        (takes [spec :Any])
        (gives :Any))

      (function "make_predicate_registration"
        "Construct a PredicateRegistration. Required: namespace, name, severity,
         kind, predicate. Optional: scope (defaults to :scope/model),
         message-template, applies-to."
        (takes [spec :Any])
        (gives :Any))

      (function "make_renderer_registration"
        "Construct a RendererRegistration. Required: tag. Optional: treatments
         (defaults to empty map)."
        (takes [spec :Any])
        (gives :Any))

      ;; Tag-presence implication (V9) from vocabulary.boundary

      (function "has_tag_with_ancestors"
        "True iff target_id carries tag_ref or any descendant tag whose ancestor
         chain includes tag_ref. Implements V9 tag-presence implication (see
         spec.TagPresenceImplication). Plan-1 limitation: :target/primitive
         only; edge / substrate targets are not matched."
        (takes [registry  :Any
                target_id :String
                tag_ref   :Any])
        (gives :Boolean)))))
