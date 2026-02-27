(ns fukan.projection.api
  "External API for the projection module.
   Composes graph, path, details, and schema projections into compound
   operations that consumers need. Consumers require only this namespace
   instead of reaching into individual projection namespaces."
  (:require [fukan.projection.graph :as graph]
            [fukan.projection.details :as details]
            [fukan.projection.path :as path]
            [fukan.projection.schema :as schema]))

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
   Accepts a node-id string, edge-id string, or schema keyword."
  {:malli/schema [:=> [:cat :Model [:or :string :keyword]] [:maybe :EntityDetails]]}
  [model entity-id]
  (if (keyword? entity-id)
    (when-let [node-id (schema/find-schema-node-id model entity-id)]
      (details/entity-details model node-id))
    (details/entity-details model entity-id)))

(defn find-root
  "Find the root node of the model.
   Delegates to path/find-root-node."
  {:malli/schema [:=> [:cat :Model] [:maybe :Node]]}
  [model]
  (path/find-root-node model))
