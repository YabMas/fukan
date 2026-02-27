(ns fukan.projection.api
  "External API for the projection module.
   Composes graph, path, details, and schema projections into compound
   operations that consumers need. Consumers require only this namespace
   instead of reaching into individual projection namespaces."
  (:require [fukan.projection.graph :as graph]
            [fukan.projection.details :as details]
            [fukan.projection.path :as path]))

(defn navigate
  "Compute the full navigation context for a view.
   Returns {:graph Projection, :path [PathSegment], :details EntityDetail?}.
   :details is only included when :selected is provided."
  {:malli/schema [:=> [:cat :Model :map] :map]}
  [model {:keys [view-id expanded selected]}]
  (let [graph-projection (graph/entity-graph model {:view-id view-id
                                                    :expanded-containers expanded})
        path-items (path/entity-path model view-id)]
    (cond-> {:graph graph-projection
             :path path-items}
      selected (assoc :details (details/entity-details model selected)))))

(defn inspect
  "Compute entity details for a single entity.
   Delegates to entity-details."
  {:malli/schema [:=> [:cat :Model :string] [:maybe :map]]}
  [model entity-id]
  (details/entity-details model entity-id))

(defn schema-lookup
  "Find a schema node and return its details.
   Returns {:node-id String?, :details EntityDetail?}."
  {:malli/schema [:=> [:cat :Model :keyword] :map]}
  [model schema-key]
  (let [node-id (path/find-schema-node-id model schema-key)]
    {:node-id node-id
     :details (when node-id (details/entity-details model node-id))}))

(defn find-root
  "Find the root node of the model.
   Delegates to path/find-root-node."
  {:malli/schema [:=> [:cat :Model] [:maybe :map]]}
  [model]
  (path/find-root-node model))
