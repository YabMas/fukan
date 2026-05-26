(ns canvas.agent.edb
  "Canvas port of agent/edb.allium + edb.boundary.

   Coverage:
     - value EDB               → construction/value (opaque type; predicate catalogue
                                  described in PredicateCatalogue invariant)
     - fn model_to_edb         → construction/function with cross-module model ref
     - 3 invariants            → vocab.behavioral/invariant each
     - exports: EDB

   Notes:
     - EDB has no declared fields — structureless opaque value type.
     - Cross-module ref model.Model uses :model/Model."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "agent.edb"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (value "EDB"
        "A map from predicate keyword to a set of tuples. The Datalog
         evaluator looks up tuples by predicate and matches positional
         arguments by unification.")

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "PredicateCatalogue"
        "The projection emits exactly these predicates with the indicated
         tuple shapes:
           :primitive/kind            [id kind]
           :primitive/label           [id label]
           :primitive/owner           [id owner-id]   -- from :owns edges
           :relation/kind             [edge-id kind from-id to-id]
           :relation/validity         [edge-id validity]
           :relation/projection-kind  [edge-id projection-kind]
           :artifact/kind             [artifact-id kind]
         Adding a new predicate is a coordinated change with the constraint
         DSL that consumes the same EDB shape."
        (holds-that "predicate-catalogue-fixed"))

      (invariant "EndpointEncoding"
        "Primitive endpoints carry their primitive id directly. Artifact
         endpoints are encoded as 'artifact:' prefixed onto the printed
         form of the artifact id vector. The query layer treats the two
         as opaque strings."
        (holds-that "artifact-endpoint-prefix-encoding"))

      (invariant "EdgeIdSynthesis"
        "Edges have no intrinsic identity in the Model; the EDB projection
         synthesises stable per-projection ids by indexing edges in
         iteration order ('edge:0', 'edge:1', ...). Ids are stable within
         a single projection pass and meaningless across rebuilds."
        (holds-that "synthetic-edge-ids"))

      ;; ── Functions ─────────────────────────────────────────────────────────

      (function "model_to_edb"
        "Project a Model value into an EDB map keyed by predicate."
        (takes [model :model/Model])
        (gives :EDB))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports EDB))))
