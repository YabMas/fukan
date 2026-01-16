(ns fukan.projection.details
  "Entity details projection functions.
   Computes dependency information for entities.")

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
   Returns {target-id -> edge-count}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)]
    (->> edges
         ;; Edges FROM this entity or its descendants
         (filter #(contains? in-subtree (:from %)))
         ;; Aggregate target to same kind
         (keep #(find-ancestor-of-kind model (:to %) kind))
         ;; Exclude self-references
         (remove #{entity-id})
         frequencies)))

(defn- compute-dependents
  "Compute dependents for an entity, aggregated to its own kind.
   Returns {source-id -> edge-count}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)]
    (->> edges
         ;; Edges TO this entity or its descendants
         (filter #(contains? in-subtree (:to %)))
         ;; Aggregate source to same kind
         (keep #(find-ancestor-of-kind model (:from %) kind))
         ;; Exclude self-references
         (remove #{entity-id})
         frequencies)))

;; -----------------------------------------------------------------------------
;; Public API

(defn entity-details
  "Compute the details for an entity.
   Returns {:node :deps :dependents} map where:
   - :node is the model node
   - :deps is {target-id -> edge-count} for dependencies
   - :dependents is {source-id -> edge-count} for dependents"
  [model entity-id]
  (let [node (get-in model [:nodes entity-id])]
    {:node node
     :deps (when node (compute-deps model entity-id))
     :dependents (when node (compute-dependents model entity-id))}))
