(ns fukan.projection.api
  "Public projection API facade.
   Provides domain queries and view computation over the model.

   Design principles:
   - Projections return pure domain data (nodes, edges, dependencies, paths)
   - Web layer owns all interaction concerns (click handlers, SSE, navigation)
   - Selection is UI state, not projection - selected?/highlighted? added by web layer
   - expanded-containers is a projection parameter (affects what data is returned)
   - selected-id is NOT a projection parameter (only affects display, handled by web)"
  (:require [fukan.projection.graph :as graph]
            [fukan.projection.details :as details]
            [fukan.projection.path :as path]
            [fukan.projection.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Graph projections

(defn entity-graph
  "Compute graph projection for any entity.

   Takes model and options map with:
   - :view-id - entity to view (defaults to root)
   - :expanded-containers - set of container IDs showing private children

   Returns {:nodes :edges :io} where:
   - nodes: vector of projection nodes (no :selected? field - that's UI state)
   - edges: vector of edges (no :highlighted? field - that's UI state)
   - io: {:inputs :outputs} schema sets for container views"
  [model opts]
  (graph/entity-graph model opts))

;; -----------------------------------------------------------------------------
;; Entity details projections

(defn entity-details
  "Get detailed information about an entity for sidebar display.

   Returns {:node :deps :dependents} where:
   - :node is the model node
   - :deps is {target-id -> edge-count} for outgoing dependencies
   - :dependents is {source-id -> edge-count} for incoming dependencies"
  [model entity-id]
  (details/entity-details model entity-id))

;; -----------------------------------------------------------------------------
;; Path projections

(defn entity-path
  "Compute breadcrumb path from root to entity.

   Returns a list of {:id :label} maps representing the navigation path.
   First element is always the root with :id nil."
  [model entity-id]
  (path/entity-path model entity-id))

(defn find-root-node
  "Find the root node of the model.
   Returns the root node (folder or namespace with parent = nil)."
  [model]
  (path/find-root-node model))

;; -----------------------------------------------------------------------------
;; Schema projections

(defn schema-info
  "Get full schema info for a schema key.

   Returns {:schema-key :schema-form :owner-ns} or nil if not found."
  [model schema-key]
  (schema/schema-info model schema-key))

(defn namespace-schemas
  "Get all schema keywords defined in a namespace.

   Returns a set of keywords."
  [model ns-str]
  (schema/schemas-for-ns model ns-str))

(defn get-schema
  "Get schema form by keyword from model.

   Returns the schema form, or nil if not found.
   Convenience function - same as (:schema-form (schema-info model k))."
  [model schema-key]
  (schema/get-schema model schema-key))

(defn extract-schema-refs
  "Extract all keyword schema references from a schema form.

   Returns a set of keywords (e.g., #{:Node :Edge :Model}).
   Only returns refs that are registered in the model's schema nodes."
  [model schema-form]
  (schema/extract-schema-refs model schema-form))
