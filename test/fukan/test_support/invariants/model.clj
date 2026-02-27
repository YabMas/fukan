(ns fukan.test-support.invariants.model
  "Model invariant predicates derived from model.allium.
   Each predicate returns true on success or a descriptive map on violation.")

;; ---------------------------------------------------------------------------
;; TreeStructure: each node has ≤1 parent; parent pointers match children
;; sets; no cycles.

(defn tree-structure?
  "Verify the tree structure invariant:
   - Each node has at most one parent (guaranteed by data structure)
   - Parent pointers are consistent with children sets
   - No cycles in parent chain"
  [{:keys [nodes]}]
  ;; Check parent→children consistency
  (or (first
        (for [[id node] nodes
              :let [parent-id (:parent node)]
              :when parent-id
              :let [parent (get nodes parent-id)]
              :when (or (nil? parent)
                        (not (contains? (:children parent) id)))]
          {:violation :tree-structure
           :node-id id
           :parent-id parent-id
           :reason (if (nil? parent)
                     "parent node does not exist"
                     "node not in parent's children set")}))
      ;; Check children→parent consistency
      (first
        (for [[id node] nodes
              child-id (:children node)
              :let [child (get nodes child-id)]
              :when (or (nil? child)
                        (not= id (:parent child)))]
          {:violation :tree-structure
           :node-id id
           :child-id child-id
           :reason (if (nil? child)
                     "child node does not exist"
                     "child's parent does not point back")}))
      ;; Check for cycles
      (first
        (for [[id _] nodes
              :let [cycle? (loop [current (:parent (get nodes id))
                                  visited #{id}
                                  depth 0]
                             (cond
                               (nil? current) false
                               (> depth (count nodes)) true
                               (contains? visited current) true
                               :else (recur (:parent (get nodes current))
                                            (conj visited current)
                                            (inc depth))))]
              :when cycle?]
          {:violation :tree-structure
           :node-id id
           :reason "cycle detected in parent chain"}))
      true))

;; ---------------------------------------------------------------------------
;; LeafStrictness: :function and :schema nodes have empty :children.

(defn leaf-strictness?
  "Verify that function and schema nodes have no children."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (#{:function :schema} (:kind node))
              :when (seq (:children node))]
          {:violation :leaf-strictness
           :node-id id
           :kind (:kind node)
           :children (:children node)}))
      true))

;; ---------------------------------------------------------------------------
;; SchemaReplacesFunction: no :function sibling shares a label with a :schema node.

(defn schema-replaces-function?
  "Verify that no function node coexists with a schema node of the same label
   under the same parent."
  [{:keys [nodes]}]
  (let [schema-labels-by-parent
        (->> (vals nodes)
             (filter #(= :schema (:kind %)))
             (group-by :parent)
             (into {} (map (fn [[p schemas]]
                             [p (set (map :label schemas))]))))]
    (or (first
          (for [[id node] nodes
                :when (= :function (:kind node))
                :let [parent (:parent node)
                      sibling-schema-labels (get schema-labels-by-parent parent)]
                :when (and sibling-schema-labels
                           (contains? sibling-schema-labels (:label node)))]
            {:violation :schema-replaces-function
             :node-id id
             :label (:label node)
             :parent parent}))
        true)))

;; ---------------------------------------------------------------------------
;; NoEmptyContainers: every :container has non-empty :children,
;; unless it carries spec data (surface, fields, or spec).

(defn- has-spec-data?
  "True if a container node has spec-derived content."
  [node]
  (let [data (:data node)]
    (or (:surface data) (seq (:fields data)) (:spec data))))

(defn no-empty-containers?
  "Verify that every container node has at least one child.
   Exception: containers with spec data are allowed to be childless."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (= :container (:kind node))
              :when (empty? (:children node))
              :when (not (has-spec-data? node))]
          {:violation :no-empty-containers
           :node-id id
           :label (:label node)}))
      true))

;; ---------------------------------------------------------------------------
;; NoSelfEdges: no edge has :from = :to.

(defn no-self-edges?
  "Verify that no edge points from a node to itself."
  [{:keys [edges]}]
  (or (first
        (for [{:keys [from to]} edges
              :when (= from to)]
          {:violation :no-self-edges
           :from from
           :to to}))
      true))

;; ---------------------------------------------------------------------------
;; EdgeIntegrity: every edge endpoint exists in :nodes.

(defn edge-integrity?
  "Verify that both endpoints of every edge exist in the nodes map."
  [{:keys [nodes edges]}]
  (or (first
        (for [{:keys [from to]} edges
              :when (or (not (contains? nodes from))
                        (not (contains? nodes to)))]
          {:violation :edge-integrity
           :from from
           :to to
           :from-exists? (contains? nodes from)
           :to-exists? (contains? nodes to)}))
      true))

;; ---------------------------------------------------------------------------
;; SmartRootPruning: root node is not a single-child container chain.

(defn smart-root-pruning?
  "Verify that the root is not a single-child container chain.
   The root should either have multiple children, or its sole child
   should not be a container (it should be a function/schema/namespace
   with vars)."
  [{:keys [nodes]}]
  (let [roots (->> (vals nodes)
                    (filter #(nil? (:parent %))))]
    (if (not= 1 (count roots))
      true
      (let [root (first roots)]
        (if (and (= :container (:kind root))
                 (= 1 (count (:children root))))
          (let [child-id (first (:children root))
                child (get nodes child-id)]
            (if (and (= :container (:kind child))
                     (empty? (remove #(= :container (:kind (get nodes %)))
                                     (:children child))))
              {:violation :smart-root-pruning
               :root-id (:id root)
               :reason "root is a single-child container chain"}
              true))
          true)))))

;; ---------------------------------------------------------------------------
;; SurfaceFunctionConsistency: every surface provides operation has a
;; corresponding Function child node.

(defn surface-function-consistency?
  "Verify that every surface provides operation has a corresponding Function
   child node under the same container."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (= :container (:kind node))
              :let [surface (get-in node [:data :surface])]
              :when surface
              :let [provides (:provides surface)]
              :when (seq provides)
              :let [child-labels (->> (:children node)
                                      (map #(get nodes %))
                                      (filter #(= :function (:kind %)))
                                      (map :label)
                                      set)]
              provide provides
              :when (not (contains? child-labels (:name provide)))]
          {:violation :surface-function-consistency
           :container-id id
           :missing-function (:name provide)
           :available-children child-labels}))
      true))

;; ---------------------------------------------------------------------------
;; Composite

(defn valid-model?
  "Run all model invariants, return first violation or true."
  [model]
  (let [checks [tree-structure?
                leaf-strictness?
                schema-replaces-function?
                no-empty-containers?
                no-self-edges?
                edge-integrity?
                smart-root-pruning?
                surface-function-consistency?]]
    (reduce (fn [_ check]
              (let [result (check model)]
                (if (true? result)
                  true
                  (reduced result))))
            true
            checks)))
