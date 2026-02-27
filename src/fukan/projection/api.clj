(ns fukan.projection.api
  "External API for the projection module.
   Composes graph, path, details, and schema projections into compound
   operations that consumers need. Consumers require only this namespace
   instead of reaching into individual projection namespaces."
  (:require [fukan.projection.graph :as graph]
            [fukan.projection.details :as details]
            [fukan.projection.path :as path]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema NavigateOpts
  [:map {:description "Options for navigation: view target, expanded containers, and optional selection."}
   [:view-id {:optional true} [:maybe :string]]
   [:expanded {:optional true} [:set :NodeId]]
   [:selected {:optional true} [:maybe :string]]])

(def ^:schema NavigateResult
  [:map {:description "Complete navigation context: graph projection, breadcrumb path, and optional entity details."}
   [:graph :Projection]
   [:path :EntityPath]
   [:details {:optional true} :EntityDetails]])

(def ^:schema SchemaLookupResult
  [:map {:description "Result of looking up a schema by keyword: the owning node and its details."}
   [:node-id [:maybe :string]]
   [:details [:maybe :EntityDetails]]])

;; -----------------------------------------------------------------------------
;; Public API

(defn navigate
  "Compute the full navigation context for a view.
   Returns {:graph Projection, :path [PathSegment], :details EntityDetail?}.
   :details is only included when :selected is provided."
  {:malli/schema [:=> [:cat :Model :NavigateOpts] :NavigateResult]}
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
  {:malli/schema [:=> [:cat :Model :string] [:maybe :EntityDetails]]}
  [model entity-id]
  (details/entity-details model entity-id))

(defn schema-lookup
  "Find a schema node and return its details.
   Returns {:node-id String?, :details EntityDetail?}."
  {:malli/schema [:=> [:cat :Model :keyword] :SchemaLookupResult]}
  [model schema-key]
  (let [node-id (path/find-schema-node-id model schema-key)]
    {:node-id node-id
     :details (when node-id (details/entity-details model node-id))}))

(defn find-root
  "Find the root node of the model.
   Delegates to path/find-root-node."
  {:malli/schema [:=> [:cat :Model] [:maybe :Node]]}
  [model]
  (path/find-root-node model))
