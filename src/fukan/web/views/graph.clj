(ns fukan.web.views.graph
  "Pure functions to compute graph data for Cytoscape visualization.
   Implements view-spec.md behavior with on-demand edge aggregation."
  (:require [fukan.web.views.common :as common]
            [fukan.schema :as schema]
            [clojure.string :as str]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; Node accessor helpers (access via :data map)

(defn- node-private? [node]
  (get-in node [:data :private?] false))

(defn- node-ns-sym [node]
  (get-in node [:data :ns-sym]))

(defn- node-var-sym [node]
  (get-in node [:data :var-sym]))

(defn- schema-defining-var?
  "Check if a var node defines a schema (has corresponding registered schema)."
  [node]
  (when (= :var (:kind node))
    (let [ns-sym (node-ns-sym node)
          var-sym (node-var-sym node)
          schema-key (keyword (str ns-sym) (str var-sym))]
      (some? (schema/get-schema schema-key)))))

;; -----------------------------------------------------------------------------
;; Visibility helpers

(defn- node-visible?
  "Check if a node is visible given the expanded-containers set.
   A node is visible if it's not private, or if its parent is expanded."
  [model node-id expanded-containers]
  (let [node (get-in model [:nodes node-id])]
    (or (not (node-private? node))
        (contains? expanded-containers (:parent node)))))

(defn- get-children
  "Get the children of an entity."
  [model entity-id]
  (get-in model [:nodes entity-id :children] #{}))

(defn- has-private-children?
  "Check if a container has any private children."
  [model entity-id]
  (let [children-ids (get-children model entity-id)]
    (some #(node-private? (get-in model [:nodes %])) children-ids)))

(defn- get-visible-children
  "Get the visible children of an entity, filtered by expanded-containers.
   When a container has both namespace and var children, prioritize namespaces."
  [model entity-id expanded-containers]
  (let [children-ids (get-children model entity-id)
        child-kinds (into #{} (map #(:kind (get-in model [:nodes %])) children-ids))
        ;; If both ns and var children, filter out vars (namespaces take precedence)
        children-ids (if (and (contains? child-kinds :namespace)
                              (contains? child-kinds :var))
                       (into #{} (filter #(#{:namespace :folder :schema}
                                           (:kind (get-in model [:nodes %])))) children-ids)
                       children-ids)]
    (if (or (nil? expanded-containers)
            (contains? expanded-containers entity-id))
      children-ids
      (into #{} (remove #(node-private? (get-in model [:nodes %]))) children-ids))))

;; -----------------------------------------------------------------------------
;; Ancestry helpers

(defn- ancestor-chain
  "Get the chain of ancestors from node to root (inclusive of node, exclusive of root).
   Returns a list starting from the node up to (but not including) nil."
  [model node-id]
  (loop [current node-id
         chain []]
    (if (nil? current)
      chain
      (recur (:parent (get-in model [:nodes current]))
             (conj chain current)))))

(defn- descendant-of?
  "Check if node-id is a descendant of ancestor-id."
  [model node-id ancestor-id]
  (loop [current (:parent (get-in model [:nodes node-id]))]
    (cond
      (nil? current) false
      (= current ancestor-id) true
      :else (recur (:parent (get-in model [:nodes current]))))))

(defn- find-visible-ancestor
  "Walk up parent chain to find first node in visible-set.
   Returns nil if no ancestor is visible."
  [model node-id visible-set]
  (loop [current node-id]
    (cond
      (nil? current) nil
      (contains? visible-set current) current
      :else (recur (:parent (get-in model [:nodes current]))))))

;; -----------------------------------------------------------------------------
;; On-demand edge aggregation

(defn- aggregate-edges
  "Aggregate var-level edges to the visible node level.
   For each raw edge, find which visible nodes it connects.
   Returns deduplicated edges between visible nodes.

   Excludes edges where one endpoint is an ancestor of the other -
   parent-child relationships are implicit in compound node structure."
  [model visible-set]
  (let [raw-edges (:edges model)]
    (->> raw-edges
         (keep (fn [{:keys [from to]}]
                 (let [from-visible (find-visible-ancestor model from visible-set)
                       to-visible (find-visible-ancestor model to visible-set)]
                   (when (and from-visible to-visible
                              (not= from-visible to-visible)
                              ;; No edges between ancestor and descendant
                              (not (descendant-of? model to-visible from-visible))
                              (not (descendant-of? model from-visible to-visible)))
                     {:from from-visible :to to-visible}))))
         distinct
         vec)))

;; -----------------------------------------------------------------------------
;; IO Schema computation

(defn- extract-fn-schema-flow
  "Extract input and output schema references from a function schema.
   Expects schema in form [:=> [:cat input1 input2 ...] output].
   Returns {:inputs #{schema-keys...} :outputs #{schema-keys...}}
   or nil if not a function schema."
  [fn-schema]
  (when (and (vector? fn-schema) (= :=> (first fn-schema)))
    (let [[_ input output] fn-schema
          in-schemas (if (and (vector? input) (= :cat (first input)))
                       (rest input)
                       [input])]
      {:inputs (into #{} (mapcat schema/extract-schema-refs in-schemas))
       :outputs (schema/extract-schema-refs output)})))

(defn- compute-var-schema-info
  "Get schema info for a var by looking up its malli/schema metadata.
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or nil."
  [node]
  (when (= :var (:kind node))
    (let [ns-sym (node-ns-sym node)
          var-sym (node-var-sym node)]
      (when-let [ns-obj (find-ns ns-sym)]
        (when-let [v (ns-resolve ns-obj var-sym)]
          (when-let [fn-schema (:malli/schema (meta v))]
            (extract-fn-schema-flow fn-schema)))))))

(defn- collect-container-schema-flow
  "Collect schema flow information for all vars inside a container.
   Returns {:produces #{schema-keys} :consumes #{schema-keys}}"
  [model container-id]
  (let [inside? (fn [node-id]
                  (or (= node-id container-id)
                      (descendant-of? model node-id container-id)))
        vars (->> (:nodes model)
                  vals
                  (filter #(and (= :var (:kind %))
                               (inside? (:id %)))))]
    (reduce (fn [acc node]
              (if-let [{:keys [inputs outputs]} (compute-var-schema-info node)]
                (-> acc
                    (update :consumes into inputs)
                    (update :produces into outputs))
                acc))
            {:consumes #{} :produces #{}}
            vars)))

(defn- compute-io-schemas
  "Compute input and output schemas for a container.
   Inputs: schemas consumed inside but NOT produced inside
   Outputs: schemas produced inside (regardless of consumption)
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}}"
  [model container-id]
  (let [{:keys [consumes produces]} (collect-container-schema-flow model container-id)
        ;; Inputs are consumed but not produced inside
        inputs (set/difference consumes produces)]
    {:inputs inputs
     :outputs produces}))

;; -----------------------------------------------------------------------------
;; View node construction

(defn- make-view-node
  "Convert a model node to a view node with rendering properties.
   Returns internal format with Clojure idioms (kebab-case, ? predicates).

   parent-override can be:
   - nil: use the node's actual parent
   - :no-parent: explicitly set parent to nil (for bounding box root)
   - any other value: use that as the parent"
  [model node parent-override expanded-containers selected-id]
  (let [id (:id node)
        kind (:kind node)
        parent (cond
                 (= parent-override :no-parent) nil
                 (some? parent-override) parent-override
                 :else (:parent node))]
    {:id id
     :kind kind
     :label (:label node)
     :parent parent
     :selected? (= id selected-id)
     :expandable? (boolean (seq (:children node)))
     :has-private-children? (has-private-children? model id)
     :expanded? (contains? expanded-containers id)
     :child-count (count (:children node))
     :private? (node-private? node)
     :schema-var? (schema-defining-var? node)}))

;; -----------------------------------------------------------------------------
;; Drill-down expansion helpers

(defn- all-descendants-of
  "Get all descendant node ids of a given node (not including the node itself)."
  [model node-id]
  (let [children (get-children model node-id)]
    (into children
          (mapcat #(all-descendants-of model %) children))))

(defn- find-direct-child-of
  "Given a descendant node-id of container-id, find the ancestor
   that is a direct child of container-id. Returns nil if node-id
   is not a descendant, or node-id itself if already a direct child."
  [model container-id node-id]
  (let [direct-children (get-in model [:nodes container-id :children])]
    (if (contains? direct-children node-id)
      node-id
      (loop [current node-id]
        (let [parent (:parent (get-in model [:nodes current]))]
          (cond
            (nil? parent) nil
            (= parent container-id) current
            :else (recur parent)))))))

(defn- find-targets-inside-container
  "Find direct children of container-id that contain actual edge targets
   from entities in external-visible-set (entities not inside the container).
   Uses raw edges to find actual targets, not aggregated ones.
   Only returns targets where the source and target have the same :kind."
  [model container-id external-visible-set]
  (let [raw-edges (:edges model)
        container-descendants (all-descendants-of model container-id)]
    (->> raw-edges
         (keep (fn [{:keys [from to]}]
                 ;; Target must be a descendant of the container
                 (when (contains? container-descendants to)
                   ;; Source must be reachable from external visible set
                   (let [from-ancestor (find-visible-ancestor model from external-visible-set)]
                     (when (and from-ancestor
                                ;; Source must not be inside the container
                                (not (contains? container-descendants from)))
                       ;; Find direct child containing the target
                       (let [target-child (find-direct-child-of model container-id to)
                             ;; Type filter: only include if source and target have same kind
                             source-kind (get-in model [:nodes from-ancestor :kind])
                             target-kind (get-in model [:nodes target-child :kind])]
                         (when (= source-kind target-kind)
                           target-child)))))))
         (remove nil?)
         set)))

(defn- expand-internal-deps
  "Expand a set of nodes to include their internal dependencies.
   Given an initial set of node ids inside a container, transitively add
   any nodes inside the same container that they depend on.
   This ensures drill-down captures complete dependency chains.

   Returns only direct children of container-id, not grandchildren."
  [model container-id initial-set]
  (let [container-descendants (all-descendants-of model container-id)]
    (loop [expanded initial-set]
      (let [new-deps (->> (:edges model)
                          (keep (fn [{:keys [from to]}]
                                  (let [from-parent (:parent (get-in model [:nodes from]))
                                        to-parent (:parent (get-in model [:nodes to]))]
                                    (when (and (contains? expanded from-parent)
                                               (contains? container-descendants to-parent))
                                      ;; Return direct child of container, not grandchild
                                      (find-direct-child-of model container-id to-parent)))))
                          (remove nil?)
                          ;; Filter out already-expanded to avoid infinite loop
                          (remove #(contains? expanded %))
                          set)]
        (if (empty? new-deps)
          expanded
          (recur (set/union expanded new-deps)))))))

;; -----------------------------------------------------------------------------
;; Container view computation

(defn- iterate-drill-down
  "Iteratively expand visible set until all edge targets are visible.

   Same-type filtering: Only drills into containers when the source and target
   are the same kind (namespace→namespace, var→var). This keeps the structural
   overview clean at each level of the hierarchy.

   Includes internal dep expansion at each step to ensure nested containers
   are discovered and processed.

   Returns {:visible-set :drill-down-map :edges} where:
   - visible-set: all entities that should be visible
   - drill-down-map: {container-id -> #{target-children}} for grouping (includes internal deps)
   - edges: final aggregated edges"
  [model initial-children-set]
  (loop [visible-set initial-children-set
         drill-down-map {}]
    (let [edges (aggregate-edges model visible-set)
          ;; Find containers that are edge targets
          container-targets (->> edges
                                 (map :to)
                                 (filter #(seq (get-in model [:nodes % :children])))
                                 set)
          ;; For each container target, find actual targets inside
          new-targets-by-container
            (into {}
              (for [cid container-targets
                    :let [;; External sources are visible entities not inside this container
                          external (into #{} (remove #(descendant-of? model % cid) visible-set))
                          targets (find-targets-inside-container model cid external)]
                    :when (seq targets)]
                [cid targets]))
          ;; Expand internal deps for each new container - this may add sibling containers
          ;; that need drilling down in the next iteration
          expanded-targets-by-container
            (into {}
              (for [[cid targets] new-targets-by-container]
                [cid (expand-internal-deps model cid targets)]))
          ;; Combine with existing drill-down-map
          updated-drill-down-map (merge-with set/union drill-down-map expanded-targets-by-container)
          ;; Only consider targets that aren't already visible
          new-targets (set/difference (into #{} (mapcat val expanded-targets-by-container)) visible-set)]
      (if (empty? new-targets)
        {:visible-set visible-set
         :drill-down-map drill-down-map
         :edges edges}
        (recur (set/union visible-set new-targets)
               updated-drill-down-map)))))

(defn- compute-container-view-impl
  "Generic container view computation. Works for any container type.
   Implements view-spec.md container behavior uniformly."
  [model entity-id expanded-containers selected-id children-ids]
  (let [children-set (set children-ids)

        ;; Iteratively expand visible set until all edge targets are visible
        ;; (includes internal dep expansion at each step)
        {:keys [drill-down-map]} (iterate-drill-down model children-set)
        drill-down-entities (into #{} (mapcat val drill-down-map))

        ;; Build final visible set
        all-visible (set/union children-set drill-down-entities)

        ;; Compute final edges with fully expanded visible set
        final-edges (aggregate-edges model all-visible)

        ;; Step 6: Build view nodes
        ;; Container node (the viewed entity - no parent for strict bounding box)
        container-node (make-view-node model (get-in model [:nodes entity-id])
                                       :no-parent expanded-containers selected-id)

        ;; Direct children
        child-nodes (for [cid children-ids
                          :let [node (get-in model [:nodes cid])]
                          :when node]
                      (make-view-node model node entity-id expanded-containers selected-id))

        ;; Drill-down entities (inside container children)
        drill-down-nodes (for [did drill-down-entities
                               :let [node (get-in model [:nodes did])
                                     parent-container (some (fn [[cid entity-set]]
                                                              (when (contains? entity-set did) cid))
                                                            drill-down-map)]
                               :when (and node parent-container)]
                           (make-view-node model node parent-container expanded-containers selected-id))

        ;; Build view edges (internal format)
        view-edges (map-indexed
                    (fn [idx {:keys [from to]}]
                      {:id (str "e" idx)
                       :from from
                       :to to
                       :edge-type :code-flow
                       :highlighted? (or (= from selected-id) (= to selected-id))})
                    final-edges)

        ;; Compute IO schemas
        io-data (compute-io-schemas model entity-id)]

    {:nodes (vec (concat [container-node] child-nodes drill-down-nodes))
     :edges (vec view-edges)
     :io io-data}))

(defn- compute-container-view
  "Compute view when the entity is a container (has children).
   Implements view-spec.md container behavior uniformly for all container types."
  [model entity-id expanded-containers selected-id]
  (let [children-ids (get-visible-children model entity-id expanded-containers)]
    (compute-container-view-impl model entity-id expanded-containers selected-id children-ids)))

;; -----------------------------------------------------------------------------
;; Leaf view computation

(defn- compute-leaf-view
  "Compute view when the entity is a leaf (no children).
   Implements view-spec.md leaf behavior.

   For vars: shows related vars grouped by their parent namespace,
   with var-level edges."
  [model entity-id expanded-containers selected-id]
  (let [raw-edges (:edges model)

        ;; Find all related vars via edges
        outgoing-var-ids (->> raw-edges
                              (filter #(= entity-id (:from %)))
                              (map :to)
                              set)
        incoming-var-ids (->> raw-edges
                              (filter #(= entity-id (:to %)))
                              (map :from)
                              set)
        related-var-ids (set/union outgoing-var-ids incoming-var-ids)

        ;; Get the parent namespaces for grouping
        entity-ns (:parent (get-in model [:nodes entity-id]))
        related-ns-ids (->> related-var-ids
                            (map #(:parent (get-in model [:nodes %])))
                            (remove nil?)
                            set)
        all-ns-ids (if entity-ns
                     (conj related-ns-ids entity-ns)
                     related-ns-ids)

        ;; Get edges at var level involving the selected entity
        relevant-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (or (= from entity-id) (= to entity-id)))))

        ;; Build namespace nodes for grouping
        ns-nodes (for [nid all-ns-ids
                       :let [node (get-in model [:nodes nid])]
                       :when node]
                   (make-view-node model node (:parent node) expanded-containers selected-id))

        ;; Build var nodes (selected + related)
        entity-node (make-view-node model (get-in model [:nodes entity-id])
                                    entity-ns expanded-containers selected-id)
        related-var-nodes (for [vid related-var-ids
                                :let [node (get-in model [:nodes vid])]
                                :when node]
                            (make-view-node model node (:parent node) expanded-containers selected-id))

        ;; Get parent folders for grouping namespace nodes
        folder-ids (->> all-ns-ids
                        (map #(:parent (get-in model [:nodes %])))
                        (remove nil?)
                        set)
        folder-nodes (for [fid folder-ids
                           :let [node (get-in model [:nodes fid])]
                           :when node]
                       (make-view-node model node nil expanded-containers selected-id))

        ;; Build view edges (internal format)
        view-edges (map-indexed
                    (fn [idx {:keys [from to]}]
                      {:id (str "e" idx)
                       :from from
                       :to to
                       :edge-type :code-flow
                       :highlighted? (or (= from entity-id) (= to entity-id))})
                    relevant-edges)]

    {:nodes (vec (concat folder-nodes ns-nodes related-var-nodes [entity-node]))
     :edges (vec view-edges)
     :io nil}))

;; -----------------------------------------------------------------------------
;; Public API

(defn compute-graph
  "Compute graph data for any entity. Pure function implementing view-spec.md.

   For containers (entities with children):
   - Shows visible children (filtered by expanded-containers)
   - Shows edges between visible children
   - Shows drill-down to entities in sibling containers when related

   For leaves (entities without children):
   - Shows the entity and all related entities
   - Groups related entities by their parent container

   Returns {:nodes :edges :io} where:
   - nodes: vector of view nodes with rendering properties
   - edges: vector of edges with highlighting
   - io: {:inputs :outputs} schema sets for container views"
  [model {:keys [view-id selected-id expanded-containers]}]
  (let [;; Default to root if no view-id
        entity-id (or view-id (:id (common/find-root-node model)))
        selected-id (or selected-id entity-id)
        expanded-containers (or expanded-containers #{})

        children-ids (get-children model entity-id)]

    (if (seq children-ids)
      (compute-container-view model entity-id expanded-containers selected-id)
      (compute-leaf-view model entity-id expanded-containers selected-id))))

(defn compute-highlighted-edges
  "Compute which edges should be highlighted for a selected node.
   For schema nodes, highlights all edges with matching schema-key.
   For other nodes, highlights edges where node is from or to.

   Works with internal edge format (:from/:to, :schema-key)."
  [edges selected-id]
  (when selected-id
    (cond
      ;; Schema nodes - highlight by schema-key
      (or (str/starts-with? selected-id "schema:")
          (str/starts-with? selected-id "input-schema:")
          (str/starts-with? selected-id "output-schema:")
          (str/starts-with? selected-id "internal-schema:"))
      (let [;; Extract schema key from various prefixes
            target-schema-key (cond
                                (str/starts-with? selected-id "input-schema:") (subs selected-id 13)
                                (str/starts-with? selected-id "output-schema:") (subs selected-id 14)
                                (str/starts-with? selected-id "internal-schema:") (subs selected-id 16)
                                (str/starts-with? selected-id "schema:") (subs selected-id 7))]
        (->> edges
             (keep (fn [{:keys [id schema-key]}]
                     (when (= schema-key target-schema-key) id)))
             vec))

      ;; Regular nodes - highlight edges by from/to
      :else
      (->> edges
           (keep (fn [{:keys [id from to]}]
                   (when (or (= from selected-id) (= to selected-id))
                     id)))
           vec))))
