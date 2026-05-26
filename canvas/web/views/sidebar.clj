(ns canvas.web.views.sidebar
  "Canvas port of web/views/sidebar.allium + sidebar.boundary.

   Coverage:
     - invariant SidebarSectionOrder    → vocab.behavioral/invariant
     - invariant ClickableSchemaRefs    → vocab.behavioral/invariant
     - invariant EdgeRendererSections   → vocab.behavioral/invariant
     - invariant SidebarEmptyState      → vocab.behavioral/invariant
     - fn render_sidebar_html           → construction/function (boundary)

   Notes:
     - EntityDetails is a model-side type; referenced as :model/EntityDetails."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "web.views.sidebar"

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "SidebarSectionOrder"
        "Non-edge sidebar sections appear in order: Label, Defined-in,
         Description, Guarantees, Invariants, Defined Types, Interface.
         Interface is dispatched by type: fn_list (modules), fn_inline
         (functions), schema_def (schemas), name_list (interfaces)."
        (holds-that "sidebar-sections-in-canonical-order"))

      (invariant "ClickableSchemaRefs"
        "Schema type references in function signatures and schema
         definitions are clickable. Clicking navigates to the schema's
         node via full app navigation (graph + breadcrumb + sidebar)."
        (holds-that "schema-refs-are-clickable"))

      (invariant "EdgeRendererSections"
        "Edge detail renders by edge_type: code_flow shows label then
         conditionally Functions Called and/or Dispatched Functions;
         schema_reference shows label + Schema References list."
        (holds-that "edge-renderer-dispatches-by-edge-type"))

      (invariant "SidebarEmptyState"
        "When no entity is selected, the sidebar shows placeholder text."
        (holds-that "sidebar-empty-state-shows-placeholder"))

      ;; ── Boundary Functions ────────────────────────────────────────────────

      (function "render_sidebar_html"
        "Render entity or edge details as sidebar HTML."
        (takes [detail :model/EntityDetails])
        (gives :Html)))))
