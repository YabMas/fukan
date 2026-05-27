(ns canvas.web.views.projection
  "Canvas port of web/views/projection.allium (Plan 2b stub; no boundary file).

   Coverage:
     - entity Projection → construction/record (2 fields)
     - entity Node       → construction/record (3 fields)
     - entity Edge       → construction/record (2 fields)
     - external entity NodeId → opaque construction/record (no fields; external stub)

   Notes:
     - This is a stub satisfying local alias resolution until the full
       projection spec is authored (likely src/fukan/projection/spec.allium).
     - Allium `entity` with structural fields → construction/record.
     - Allium `external entity` (NodeId) → opaque stub record with no fields."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "web.views.projection"

      ;; ── External stub ─────────────────────────────────────────────────────

      ;; external entity NodeId — opaque identifier type
      (record "NodeId"
        "Opaque node identifier. External entity stub; internal structure
         not specified here."
        (field id :String))

      ;; ── Entities ──────────────────────────────────────────────────────────

      (record "Node"
        "A projection node. id, label, and optional parent."
        (field id     :NodeId)
        (field label  :String)
        (field parent (optional :NodeId)))

      (record "Edge"
        "A projection edge between two nodes."
        (field source :NodeId)
        (field target :NodeId))

      (record "Projection"
        "Stub projection type referenced by graph.allium.
         The real projection module spec is intended elsewhere."
        (field nodes (list-of :Node))
        (field edges (list-of :Edge))))))
