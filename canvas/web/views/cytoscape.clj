(ns canvas.web.views.cytoscape
  "Canvas port of web/views/cytoscape.allium (no boundary file — module is open).

   Coverage:
     - value CytoscapeGraph  → construction/record (4 fields)
     - value CytoscapeNode   → construction/record (8 fields, several optional)
     - value CytoscapeEdge   → construction/record (7 fields, several optional)
     - invariant CytoscapeIdentifiers           → vocab.behavioral/invariant
     - invariant CytoscapeEdgeTypeMirrorsKind   → vocab.behavioral/invariant
     - invariant CytoscapeProjectionFieldsScope → vocab.behavioral/invariant

   Notes:
     - Allium `value` with exposed fields → construction/record (opaque `value`
       would suppress all field structure; `record` is the correct lift here).
     - model.TagRef         → :model/TagRef   (cross-module ref)
     - model.SourceLocation → :model/SourceLocation (cross-module ref)
     - All three record types are externally visible (module open by default)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "web.views.cytoscape"

      ;; ── Value Types (as records — fields are structurally significant) ────

      (record "CytoscapeGraph"
        "Output payload for the Cytoscape.js frontend. Domain data
         transformed to Cytoscape conventions: from/to → source/target,
         kebab-case → camelCase."
        (field nodes             (list-of :CytoscapeNode))
        (field edges             (list-of :CytoscapeEdge))
        (field selected_id       (optional :String))
        (field highlighted_edges (list-of :String)))

      (record "CytoscapeNode"
        "A renderable node in the Cytoscape graph. Materialises either a
         kernel primitive or a code artifact."
        (field id              :String)
        (field kind            :String)
        (field label           :String)
        (field parent          (optional :String))
        (field treatment       (optional (map-of :String :Value)))
        (field tags            (optional (list-of :model/TagRef)))
        (field source_location (optional :model/SourceLocation))
        (field selected        :Boolean))

      (record "CytoscapeEdge"
        "A renderable edge in the Cytoscape graph. Materialises a kernel
         relation edge between two endpoints, including projects edges
         which carry projection metadata."
        (field id              :String)
        (field source          :String)
        (field target          :String)
        (field kind            :String)
        (field edge_type       :String)
        (field projection_kind (optional :String))
        (field drift           (optional :String)))

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "CytoscapeIdentifiers"
        "Every node carries a non-empty stable id used as a Cytoscape
         element id and as the source/target referent for edges; every
         edge names both endpoints by such an id."
        (holds-that "every-node-and-edge-has-stable-id"))

      (invariant "CytoscapeEdgeTypeMirrorsKind"
        "edge_type is a legacy alias of kind; the two fields carry the
         same value on every edge. New consumers should read kind."
        (holds-that "edge-type-equals-kind"))

      (invariant "CytoscapeProjectionFieldsScope"
        "projection_kind and drift are emitted only on edges whose kind
         is \"relation/projects\". drift is present iff the underlying
         projects edge has validity :absent, in which case its value is
         \"absent\"."
        (holds-that "projection-fields-scoped-to-projects-edges")))))
