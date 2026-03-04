(ns fukan.projection.api
  "External API for the projection module.
   Composes graph, path, details, and schema projections into compound
   operations that consumers need. Consumers require only this namespace
   instead of reaching into individual projection namespaces."
  (:require [clojure.string :as str]
            [fukan.projection.graph :as graph]
            [fukan.projection.details :as details]
            [fukan.projection.path :as path]
            [fukan.projection.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema NavigateOpts
  [:map {:description "Options for navigation: view target, expanded modules, optional selection, and visible edge types."}
   [:view-id {:optional true} [:maybe :NodeId]]
   [:expanded {:optional true} [:set :NodeId]]
   [:show-private {:optional true} [:set :NodeId]]
   [:selected {:optional true} [:maybe :NodeId]]
   [:visible-edge-types {:optional true} [:set :ProjectionEdgeType]]])

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
  [model {:keys [view-id expanded show-private selected visible-edge-types]}]
  (let [graph-projection (graph/entity-graph model {:view-id view-id
                                                    :expanded expanded
                                                    :show-private show-private
                                                    :visible-edge-types visible-edge-types})
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

;; -----------------------------------------------------------------------------
;; Search and Overview

(def ^:schema SearchResult
  [:map {:description "A single search match: node identity and location."}
   [:id :NodeId]
   [:kind :NodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe :NodeId]]])

(def ^:schema ModelOverview
  [:map {:description "Summary statistics for the loaded model."}
   [:total-nodes :int]
   [:total-edges :int]
   [:by-kind [:map-of :NodeKind :int]]])

(defn search
  "Case-insensitive substring search on node labels.
   Returns up to `limit` matches as [{:id :kind :label :parent}]."
  {:malli/schema [:=> [:cat :Model :string :int] [:vector :SearchResult]]}
  [model pattern limit]
  (let [pat (str/lower-case pattern)]
    (->> (vals (:nodes model))
         (filter #(str/includes? (str/lower-case (:label %)) pat))
         (take limit)
         (mapv (fn [n]
                 {:id (:id n)
                  :kind (:kind n)
                  :label (:label n)
                  :parent (:parent n)})))))

(defn overview
  "Model summary: total nodes, total edges, and counts by node kind."
  {:malli/schema [:=> [:cat :Model] :ModelOverview]}
  [model]
  (let [nodes (vals (:nodes model))]
    {:total-nodes (count nodes)
     :total-edges (count (:edges model))
     :by-kind (frequencies (map :kind nodes))}))
