(ns canvas.web.views.breadcrumb
  "Canvas port of web/views/breadcrumb.allium + breadcrumb.boundary.

   Coverage:
     - invariant BreadcrumbShortLabels   → vocab.behavioral/invariant
     - invariant BreadcrumbCurrentItem   → vocab.behavioral/invariant
     - invariant BreadcrumbClickableItems → vocab.behavioral/invariant
     - fn render_breadcrumb              → construction/function (boundary)

   Notes:
     - EntityPath is a model-side type; referenced as :model/EntityPath."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "web.views.breadcrumb"

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "BreadcrumbShortLabels"
        "Namespace names show only the last dotted segment."
        (holds-that "breadcrumb-shows-last-segment-only"))

      (invariant "BreadcrumbCurrentItem"
        "The last breadcrumb item is non-clickable and styled as current."
        (holds-that "last-item-is-non-clickable-current"))

      (invariant "BreadcrumbClickableItems"
        "All non-last breadcrumb items dispatch navigation events."
        (holds-that "non-last-items-are-clickable"))

      ;; ── Boundary Functions ────────────────────────────────────────────────

      (function "render_breadcrumb"
        "Render the navigation breadcrumb trail."
        (takes [path :model/EntityPath])
        (gives :Html)))))
