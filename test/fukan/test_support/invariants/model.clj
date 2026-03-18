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
;; NoEmptyModules: every :module has non-empty :children,
;; unless it has a surface declaration.

(defn- has-spec-data?
  "True if a module node has a meaningful boundary declaration.
   An empty boundary {:description nil} with no functions does not count —
   spec-only modules must have non-nil description or declared functions."
  [node]
  (let [boundary (:boundary (:data node))]
    (and boundary
         (or (:description boundary)
             (seq (:functions boundary))))))

(defn no-empty-modules?
  "Verify that every module node has at least one child.
   Exception: modules with spec data are allowed to be childless."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (= :module (:kind node))
              :when (empty? (:children node))
              :when (not (has-spec-data? node))]
          {:violation :no-empty-modules
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
;; SmartRootPruning: root node is not a single-child module chain.

(defn smart-root-pruning?
  "Verify that the root is not a single-child module chain.
   The root should either have multiple children, or its sole child
   should not be a module (it should be a function/schema/namespace
   with vars)."
  [{:keys [nodes]}]
  (let [roots (->> (vals nodes)
                    (filter #(nil? (:parent %))))]
    (if (not= 1 (count roots))
      true
      (let [root (first roots)]
        (if (and (= :module (:kind root))
                 (= 1 (count (:children root))))
          (let [child-id (first (:children root))
                child (get nodes child-id)]
            (if (and (= :module (:kind child))
                     (empty? (remove #(= :module (:kind (get nodes %)))
                                     (:children child))))
              {:violation :smart-root-pruning
               :root-id (:id root)
               :reason "root is a single-child module chain"}
              true))
          true)))))

;; ---------------------------------------------------------------------------
;; NoUnconsumedProvides: no module's surface still has :provides
;; (they should be materialized as Function children during build).

(defn no-unconsumed-provides?
  "Verify that no module still has :surface in the final model.
   The build pipeline should collapse surfaces into boundaries."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (= :module (:kind node))
              :when (get-in node [:data :surface])]
          {:violation :no-unconsumed-provides
           :node-id id
           :reason "surface should be collapsed into boundary"}))
      true))

;; ---------------------------------------------------------------------------
;; LeafEdges: every edge endpoint is a leaf node (no children).

(defn leaf-edges?
  "Verify that both endpoints of every edge are leaf nodes (function or schema,
   not module). Module-level edges are derived on demand by projection."
  [{:keys [nodes edges]}]
  (or (first
        (for [{:keys [from to]} edges
              :let [from-node (get nodes from)
                    to-node (get nodes to)]
              :when (or (seq (:children from-node))
                        (seq (:children to-node)))]
          {:violation :leaf-edges
           :from from
           :to to
           :from-kind (:kind from-node)
           :to-kind (:kind to-node)
           :reason (cond
                     (and (seq (:children from-node))
                          (seq (:children to-node)))
                     "both endpoints are non-leaf"
                     (seq (:children from-node))
                     "from endpoint is non-leaf"
                     :else
                     "to endpoint is non-leaf")}))
      true))

;; ---------------------------------------------------------------------------
;; EdgeHasKind: every edge has a :kind field.

(defn edge-has-kind?
  "Verify that every edge has a :kind field."
  [{:keys [edges]}]
  (or (first
        (for [{:keys [from to kind]} edges
              :when (nil? kind)]
          {:violation :edge-has-kind
           :from from
           :to to
           :reason "edge missing :kind field"}))
      true))

;; ---------------------------------------------------------------------------
;; EdgeKindEndpoints: function-call edges connect functions;
;; schema-reference edges connect schemas.

(defn edge-kind-endpoints?
  "Verify that edge kind matches endpoint node kinds.
   function-call: from is :function, to is :function or :schema.
   dispatches: both endpoints are :function nodes.
   schema-reference: both endpoints are :schema nodes."
  [{:keys [nodes edges]}]
  (or (first
        (for [{:keys [from to kind]} edges
              :let [from-node (get nodes from)
                    to-node (get nodes to)]
              :when (case kind
                      :function-call
                      (or (not= :function (:kind from-node))
                          (not (#{:function :schema} (:kind to-node))))
                      :dispatches
                      (or (not= :function (:kind from-node))
                          (not= :function (:kind to-node)))
                      :schema-reference
                      (or (not= :schema (:kind from-node))
                          (not= :schema (:kind to-node)))
                      false)]
          {:violation :edge-kind-endpoints
           :from from :from-kind (:kind from-node)
           :to to :to-kind (:kind to-node)
           :edge-kind kind
           :reason "edge kind does not match endpoint node kinds"}))
      true))

;; ---------------------------------------------------------------------------
;; ModuleHasBoundary: every module node has a boundary in :data.

(defn module-has-boundary?
  "Verify that every module node has a :boundary in its :data map."
  [{:keys [nodes]}]
  (or (first
        (for [[id node] nodes
              :when (= :module (:kind node))
              :when (not (get-in node [:data :boundary]))]
          {:violation :module-has-boundary
           :node-id id
           :label (:label node)
           :reason "module missing :boundary in :data"}))
      true))

;; ---------------------------------------------------------------------------
;; Composite

(defn valid-model?
  "Run all model invariants, return first violation or true."
  [model]
  (let [checks [tree-structure?
                leaf-strictness?
                schema-replaces-function?
                no-empty-modules?
                no-self-edges?
                edge-integrity?
                leaf-edges?
                smart-root-pruning?
                no-unconsumed-provides?
                edge-has-kind?
                edge-kind-endpoints?
                module-has-boundary?]]
    (reduce (fn [_ check]
              (let [result (check model)]
                (if (true? result)
                  true
                  (reduced result))))
            true
            checks)))
