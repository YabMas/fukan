(ns canvas.vocabulary.allium.renderers
  "Canvas port of vocabulary/allium/renderers.allium.

   Scope: Default visual treatments shipped with the Allium vocabulary —
   one node-icon registration per role-naming tag.

   Coverage:
     - 4 invariants: NodeIconCoverage, IconNameLowercase,
       NonRoleTagsHaveNoNodeTreatment, RegistrationIsIdempotent"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.renderers"

      (invariant "NodeIconCoverage"
        "Every role-naming Allium primitive tag has a corresponding node-
         icon RendererRegistration: Module, Entity, Value, Variant,
         ExternalEntity, Surface, Contract, Rule, Event, Actor, Call.
         The projection layer walks these registrations to assemble the
         per-primitive treatment map."
        (holds-that "every-role-tag-has-node-icon-registration"))

      (invariant "IconNameLowercase"
        "The node treatment payload uses {\"icon\": \"<lowercased-tag-name>\"}.
         Conversion is mechanical: lower-case the tag name verbatim. No
         per-tag override or aliasing."
        (holds-that "icon-name-is-lowercased-tag-name"))

      (invariant "NonRoleTagsHaveNoNodeTreatment"
        "Source-clause sub-substrate tags (Invariant, Requires, Ensures,
         Let, ContractInvariant, SurfaceGuarantee, Guidance) and edge-
         shading tags (Trigger, Provides, Exposes, Fulfils, Demands) do
         not register a node treatment — they do not attach to a primitive
         node in the projection. Adding a node treatment for them would
         be a category error."
        (holds-that "non-role-tags-have-no-node-treatment"))

      (invariant "RegistrationIsIdempotent"
        "Registering the catalogue onto the same Model twice produces the
         same Model. RendererRegistration identity is (consumer, tag);
         duplicates are skipped on the second pass."
        (holds-that "renderer-registration-idempotent")))))
