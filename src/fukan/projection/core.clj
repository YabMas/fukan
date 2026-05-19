(ns fukan.projection.core
  "Model → cytoscape-compatible graph projection.

   Plan 6 replaces the OLD code-graph projection (per DESIGN.md line 493)
   with one that consumes the new Model (primitives + thirteen kernel
   relations + artifacts). Existing web/views/cytoscape.clj stays as the
   format boundary; only the projection input changes.

   Tasks 7-10 fill this in.")

(defn project-model
  "Project a Model into {:nodes :edges} ready for cytoscape transformation.
   Stub: returns empty graph. Tasks 7-10 implement primitives, artifacts,
   thirteen relation kinds, and drift decoration."
  [_model]
  {:nodes [] :edges []})
