(ns canvas.target.clojure.blueprint
  "Canvas port of target/clojure/blueprint.allium + blueprint.boundary.

   Coverage:
     - value Blueprint    → construction/record (9 fields, cross-module ref)
     - fn make            → construction/function
     - fn identity        → construction/function
     - fn to_edn          → construction/function
     - fn to_markdown     → construction/function
     - 4 invariants       → vocab.behavioral/invariant each

   Notes:
     - Blueprint.address field uses :address/CanonicalAddress cross-module
       ref; :references relation emitted automatically.
     - Blueprint.signature and Blueprint.rendered are optional Any fields
       expressed via (optional :Any).
     - Blueprint.idioms is List<Any> → (list-of :Any).
     - fn identity returns List<Any> (the identity pair)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.blueprint"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "Blueprint"
        "A v1 Implementation Blueprint. Carries everything an LLM generator
         needs to produce the code artifact for a spec primitive at a given
         projection kind: the canonical address to write to, the kind of
         artifact to produce, the expected signature, surrounding model
         context, and any applicable project idioms. The rendered field
         caches both the EDN and markdown serialisations of the Blueprint.

         case is the v1 discriminator; future Blueprint revisions would
         introduce a new case value and a parallel value type."
        (field case            :String)
        (field primitive_id    :String)
        (field projection_kind :String)
        (field address         :address/CanonicalAddress)
        (field artifact_kind   :String)
        (field signature       (optional :Any))
        (field context         :Any)
        (field idioms          (list-of :Any))
        (field rendered        (optional :Any)))

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "Ephemeral"
        "Blueprints are never persisted in the Model. Each Projector
         invocation regenerates the Blueprint from the current spec, the
         current project registry, and the current Model state. Stale
         Blueprints cannot exist because no caller is allowed to hold one
         across edits."
        (holds-that "blueprints-never-persisted"))

      (invariant "IdentityIsKey"
        "A Blueprint's identity is the pair (primitive_id, projection_kind).
         Two Blueprints with the same identity regenerated against the same
         spec / registry / model are equal value-by-value."
        (holds-that "blueprint-identity-is-primitive-id-projection-kind-pair"))

      (invariant "EdnRoundtripCanonical"
        "The EDN serialisation of a Blueprint roundtrips: parsing the EDN
         form yields a value equal to the original Blueprint record. EDN is
         the canonical wire form for inspection and for the Analyzer's
         verification path."
        (holds-that "blueprint-edn-roundtrips"))

      (invariant "DualSerialisation"
        "Every assembled Blueprint carries both serialisations under
         :rendered — :edn for machine consumers, :markdown for LLM prompts
         and human inspection. The two views describe the same Blueprint and
         never disagree."
        (holds-that "blueprint-dual-serialisation-edn-and-markdown"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "make"
        "Construct a Blueprint v1 from a map of required and optional fields.
         Missing optional fields default to empty."
        (takes [input :Any])
        (gives :Blueprint))

      (function "identity"
        "Return the Blueprint's identity pair — (primitive_id,
         projection_kind) — for inspection caching keys."
        (takes [blueprint :Blueprint])
        (gives (list-of :Any)))

      (function "to_edn"
        "Serialise a Blueprint to canonical EDN. Used by the Analyzer's
         verification path and by inspection tooling."
        (takes [blueprint :Blueprint])
        (gives :String))

      (function "to_markdown"
        "Serialise a Blueprint to a markdown prompt. Used as the LLM
         generation prompt and for human inspection."
        (takes [blueprint :Blueprint])
        (gives :String)))))
