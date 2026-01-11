# fukan.web Package Conventions

## Naming Conventions in `fukan.web.views`

### `compute-X-` functions (private)
- **Input:** model + identifier (e.g., entity-id, node-id)
- **Output:** data map
- **Purpose:** Query the model, aggregate and transform data
- **Example:** `(compute-sidebar- model node-id)` returns `{:node ... :deps ... :dependents ...}`

### `render-X` functions (public)
- **Input:** model + editor-state map
- **Output:** HTML string (or JSON data for graph)
- **Purpose:** Compute data and render output for a UI component
- **Example:** `(render-sidebar model {:selected-id "var:foo/bar"})` returns HTML

### Typical internal pattern
```clojure
(defn render-sidebar [model {:keys [selected-id]}]
  (let [data (compute-sidebar- model selected-id)]
    (render-sidebar-html data)))
```

## Public API

```clojure
;; Render functions (model + editor-state -> output)
(render-app-shell)                     ; -> HTML (no args)
(render-graph model editor-state)      ; -> Cytoscape JSON data
(render-breadcrumb model editor-state) ; -> HTML
(render-sidebar model editor-state)    ; -> HTML

;; editor-state shape:
;; {:selected-id "var:foo/bar"  ; highlighted node
;;  :view-id "ns:foo"}          ; container being viewed (nil = root)
```
