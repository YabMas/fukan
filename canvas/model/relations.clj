(ns canvas.model.relations
  "Canvas port of model/relations.allium + relations.boundary.

   Coverage:
     - 2 invariants from relations.allium → vocab.behavioral/invariant each:
         EdgeIdentityIsPerRelation, MakeEdgeValidatesRelationKind
     - Endpoint constructors:
         primitive_ref, substrate_address, artifact_ref
     - Edge construction and identity:
         make_edge, identifying_slots, edge_identity

   Notes:
     - The thirteen RelationKind enum, Edge value type, and Endpoint sum live
       in canvas.model.spec.
     - edge_identity: (from, to, kind, identifying-subset) per §4.4.
     - identifying-subset is per-relation: see spec.Edge::IdentifyingMetadataPerRelation."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "model.relations"

      ;; Invariants from relations.allium

      (invariant "MakeEdgeValidatesRelationKind"
        "make_edge raises when kind is not one of the thirteen kernel
         RelationKind values (spec.RelationKind). Construction never yields an
         edge with an unknown kind."
        (holds-that "make-edge-raises-on-unknown-relation-kind"))

      (invariant "EdgeIdentityIsPerRelation"
        "edge_identity projects each edge onto (from, to, kind,
         identifying-subset), where the identifying subset is the per-relation
         slot set from §4.4 (see spec.Edge::IdentifyingMetadataPerRelation).
         Non-identifying slots are dropped. Two edges agreeing on this
         projection are the same edge."
        (holds-that "edge-identity-is-per-relation-identifying-subset"))

      ;; Endpoint constructors from relations.boundary

      (function "primitive_ref"
        "PrimitiveRef endpoint addressing a kernel primitive by id."
        (takes [id :String])
        (gives :Any))

      (function "substrate_address"
        "SubstrateAddress endpoint addressing a sub-substrate slot on a
         Container (v0: field paths)."
        (takes [container_id :String
                path         (list-of :Any)])
        (gives :Any))

      (function "artifact_ref"
        "ArtifactRef endpoint addressing a projection target by its
         artifact-identity tuple."
        (takes [artifact_identity :Any])
        (gives :Any))

      ;; Edge construction and identity

      (function "make_edge"
        "Construct a kernel edge. metadata carries the per-relation
         identifying-metadata slots (see identifying_slots) plus any
         non-identifying methodology metadata."
        (takes [kind     :String
                from     :Any
                to       :Any
                metadata :Any])
        (gives :Any))

      (function "identifying_slots"
        "The set of identifying-metadata slot names for the given relation kind,
         per §4.4."
        (takes [relation_kind :String])
        (gives (set-of :String)))

      (function "edge_identity"
        "Returns (from, to, kind, identifying-subset) per §4.4. Two edges are
         the same edge iff their identities are equal."
        (takes [edge :Any])
        (gives (tuple-of :Any :Any :String :Any))))))
