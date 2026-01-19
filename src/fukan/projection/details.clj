(ns fukan.projection.details
  "Entity details projection functions.
   Computes dependency information for entities."
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Private helpers

(defn- find-ancestor-of-kind
  "Find the first ancestor (or self) of the given kind.
   Returns nil if no ancestor of that kind exists."
  [model node-id target-kind]
  (loop [current node-id]
    (when current
      (let [node (get-in model [:nodes current])]
        (if (= target-kind (:kind node))
          current
          (recur (:parent node)))))))

(defn- subtree
  "Return set of node-id and all its descendants.
   For edge filtering: includes edges from the entity or anything inside it."
  [model node-id]
  (let [node (get-in model [:nodes node-id])]
    (if-let [children (:children node)]
      (into #{node-id} (mapcat #(subtree model %) children))
      #{node-id})))

(defn- compute-deps
  "Compute dependencies for an entity, aggregated to its own kind.
   Returns {target-id -> {:count n :label str}}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)
        freqs (->> edges
                   ;; Edges FROM this entity or its descendants
                   (filter #(contains? in-subtree (:from %)))
                   ;; Aggregate target to same kind
                   (keep #(find-ancestor-of-kind model (:to %) kind))
                   ;; Exclude self-references
                   (remove #{entity-id})
                   frequencies)]
    (into {}
          (map (fn [[target-id cnt]]
                 [target-id {:count cnt
                             :label (:label (get-in model [:nodes target-id]))}]))
          freqs)))

(defn- compute-dependents
  "Compute dependents for an entity, aggregated to its own kind.
   Returns {source-id -> {:count n :label str}}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)
        freqs (->> edges
                   ;; Edges TO this entity or its descendants
                   (filter #(contains? in-subtree (:to %)))
                   ;; Aggregate source to same kind
                   (keep #(find-ancestor-of-kind model (:from %) kind))
                   ;; Exclude self-references
                   (remove #{entity-id})
                   frequencies)]
    (into {}
          (map (fn [[source-id cnt]]
                 [source-id {:count cnt
                             :label (:label (get-in model [:nodes source-id]))}]))
          freqs)))

;; -----------------------------------------------------------------------------
;; Edge Details

(defn- parse-edge-id
  "Parse an edge ID into components.
   Edge ID format: edge~{from-id}~{to-id}~{edge-type}
   Uses ~ as delimiter since it's URL-safe and won't appear in UUIDs.
   Returns {:from-id :to-id :edge-type} or nil if invalid."
  [edge-id]
  (when (str/starts-with? edge-id "edge~")
    (let [parts (str/split (subs edge-id 5) #"~")]
      (when (= 3 (count parts))
        {:from-id (first parts)
         :to-id (second parts)
         :edge-type (keyword (nth parts 2))}))))

(defn- get-var-schema
  "Get the malli schema from a var's metadata, if present."
  [ns-sym var-sym]
  (try
    (when-let [v (ns-resolve (find-ns ns-sym) var-sym)]
      (:malli/schema (meta v)))
    (catch Exception _ nil)))

(defn- compute-underlying-edges
  "Find all var-level edges that aggregate to this visible edge.
   Returns a vector of {:from-var {:id :label :signature} :to-var {...}}
   Only includes edges where both endpoints are vars (excludes require relationships)."
  [model from-id to-id]
  (let [from-subtree (subtree model from-id)
        to-subtree (subtree model to-id)
        raw-edges (:edges model)
        ;; Find all raw edges where source is in from-subtree and target is in to-subtree
        ;; Filter to only var-level edges (both endpoints must be vars)
        matching-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? from-subtree from)
                                           (contains? to-subtree to)
                                           (= :var (:kind (get-in model [:nodes from])))
                                           (= :var (:kind (get-in model [:nodes to])))))))]
    (->> matching-edges
         (map (fn [{:keys [from to]}]
                (let [from-node (get-in model [:nodes from])
                      to-node (get-in model [:nodes to])
                      from-ns-sym (get-in from-node [:data :ns-sym])
                      from-var-sym (get-in from-node [:data :var-sym])
                      to-ns-sym (get-in to-node [:data :ns-sym])
                      to-var-sym (get-in to-node [:data :var-sym])]
                  {:from-var {:id from
                              :label (:label from-node)
                              :signature (get-var-schema from-ns-sym from-var-sym)}
                   :to-var {:id to
                            :label (:label to-node)
                            :signature (get-var-schema to-ns-sym to-var-sym)}})))
         (sort-by (fn [e] [(get-in e [:from-var :label]) (get-in e [:to-var :label])]))
         vec)))

(defn- compute-edge-details
  "Compute details for an edge entity.
   Returns data structure suitable for edge sidebar rendering."
  [model edge-id]
  (when-let [{:keys [from-id to-id edge-type]} (parse-edge-id edge-id)]
    (let [from-node (get-in model [:nodes from-id])
          to-node (get-in model [:nodes to-id])
          underlying-edges (compute-underlying-edges model from-id to-id)]
      {:node {:kind :edge
              :label (str (:label from-node) " → " (:label to-node))
              :data {:from-id from-id
                     :to-id to-id
                     :from-label (:label from-node)
                     :to-label (:label to-node)
                     :edge-type edge-type}}
       :underlying-edges underlying-edges
       :total-count (count underlying-edges)})))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema EntityDepInfo
  [:map
   [:count :int]
   [:label :string]])

(def ^:schema EntityDeps
  [:map-of :string :EntityDepInfo])

(def ^:schema EntityDetails
  [:map
   [:node [:maybe :Node]]
   [:deps [:maybe :EntityDeps]]
   [:dependents [:maybe :EntityDeps]]])

;; -----------------------------------------------------------------------------
;; Public API

(defn entity-details
  "Compute the details for an entity (node or edge).
   Edge IDs have format: edge~from-id~to-id~edge-type

   For nodes, returns {:node :deps :dependents} map where:
   - :node is the model node
   - :deps is {target-id -> {:count n :label str}} for dependencies
   - :dependents is {source-id -> {:count n :label str}} for dependents

   For edges, returns {:node :underlying-edges :total-count} where:
   - :node is a synthetic node with :kind :edge
   - :underlying-edges is a vector of caller→callee pairs
   - :total-count is the number of underlying edges"
  {:malli/schema [:=> [:cat :Model :string] :EntityDetails]}
  [model entity-id]
  (if (str/starts-with? (or entity-id "") "edge~")
    (compute-edge-details model entity-id)
    (let [node (get-in model [:nodes entity-id])]
      {:node node
       :deps (when node (compute-deps model entity-id))
       :dependents (when node (compute-dependents model entity-id))})))
