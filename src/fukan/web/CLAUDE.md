# fukan.web Package Conventions

## Architecture: Handler as Orchestrator

```
sse.clj (handler) → projection.api (fetch data)
                  → views.api (render output)
```

The handler is responsible for:
1. Parsing request parameters
2. Calling projection functions to get data
3. Passing projections to view functions for rendering

Views are pure renderers - they don't fetch data.

## Naming Conventions

### In `fukan.web.sse` (handlers)
- `compute-X-data` (private): Pre-compute data for views by calling projections
- `X-handler` (public): SSE endpoint that orchestrates projection + rendering

### In `fukan.web.views`
- `render-X` (public): Accept pre-computed data, return HTML/JSON
- `render-X-html` (private): Internal HTML rendering helpers

## Public API

```clojure
;; views.api - render functions accept projections
(render-app-shell)                                    ; -> HTML (no args)
(render-graph graph-projection root-node editor-state) ; -> Cytoscape JSON
(render-breadcrumb path-items)                        ; -> HTML
(render-sidebar sidebar-data)                         ; -> HTML

;; sse.clj - handlers orchestrate
(main-view-handler model request)  ; streams graph + breadcrumb + sidebar
(sidebar-handler model request)    ; streams sidebar only
(schema-handler model request)     ; streams schema detail
```
