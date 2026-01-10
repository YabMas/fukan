(ns fukan.cytoscape
  "Cytoscape.js format transformations.
   Converts internal model structures to Cytoscape-compatible data."
  (:require [fukan.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:private CytoscapeNode
  [:map
   [:id :string]
   [:label :string]
   [:kind :string]
   [:originalParent {:optional true} :string]
   [:ns {:optional true} :string]
   [:doc {:optional true} :string]
   [:private {:optional true} :boolean]
   [:childCount {:optional true} :int]
   ;; Schema-specific fields
   [:schemaKey {:optional true} :string]
   [:ownerNs {:optional true} :string]])

(schema/register! :fukan.cytoscape/CytoscapeNode CytoscapeNode)

(defn node->cytoscape
  "Convert an internal node to Cytoscape node format for the view API.
   Returns a flat map with :originalParent to preserve parent info
   without triggering Cytoscape compound behavior."
  {:malli/schema [:=> [:cat :fukan.model/Node] :fukan.cytoscape/CytoscapeNode]}
  [{:keys [id kind label parent ns-sym children doc private? schema-key owner-ns]}]
  (let [base {:id id
              :label label
              :kind (name kind)}]
    (cond-> base
      parent (assoc :originalParent parent)
      ns-sym (assoc :ns (str ns-sym))
      doc (assoc :doc doc)
      private? (assoc :private true)
      (seq children) (assoc :childCount (count children))
      ;; Schema-specific fields
      schema-key (assoc :schemaKey (str (namespace schema-key) "/" (name schema-key)))
      owner-ns (assoc :ownerNs owner-ns))))
