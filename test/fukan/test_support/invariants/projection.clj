(ns fukan.test-support.invariants.projection
  "Projection invariant predicates derived from projection.allium.
   Each predicate takes (model, opts, projection) and returns true
   on success or a descriptive map on violation.")

;; ---------------------------------------------------------------------------
;; Helpers

(defn- projection-node-ids
  "Get set of node IDs from a projection."
  [projection]
  (into #{} (map :id) (:nodes projection)))

(defn- descendant-of?
  "Check if node-id is a descendant of ancestor-id in the model."
  [model node-id ancestor-id]
  (loop [current (:parent (get-in model [:nodes node-id]))]
    (cond
      (nil? current) false
      (= current ancestor-id) true
      :else (recur (:parent (get-in model [:nodes current]))))))

(defn- is-leaf-view?
  "True if the view-id is a leaf (no children in model)."
  [model view-id]
  (empty? (get-in model [:nodes view-id :children] #{})))

;; ---------------------------------------------------------------------------
;; StrictBoundingBox: only entities inside the viewed module appear.

(defn strict-bounding-box?
  "Verify that only entities inside the viewed module appear."
  [model opts projection]
  (let [view-id (:view-id opts)
        node-ids (projection-node-ids projection)]
    (or (first
          (for [nid node-ids
                :when (and (not= nid view-id)
                           (not (descendant-of? model nid view-id)))]
            {:violation :strict-bounding-box
             :node-id nid
             :view-id view-id
             :reason "node is outside the viewed module"}))
        true)))

;; ---------------------------------------------------------------------------
;; NoAncestorDescendantEdges: no edge connects a module to its descendant.

(defn no-ancestor-descendant-edges?
  "Verify that no edge connects a node to its own ancestor or descendant."
  [model _opts projection]
  (or (first
        (for [{:keys [from to edge-type]} (:edges projection)
              :when (or (descendant-of? model to from)
                        (descendant-of? model from to))]
          {:violation :no-ancestor-descendant-edges
           :from from
           :to to
           :edge-type edge-type}))
      true))

;; ---------------------------------------------------------------------------
;; PrivateVisibility: private children hidden unless parent expanded.

(defn private-visibility?
  "Verify that private nodes are hidden when their parent is not expanded."
  [model opts projection]
  (let [expanded (:show-private opts #{})
        visible-ids (projection-node-ids projection)]
    (or (first
          (for [nid visible-ids
                :let [model-node (get-in model [:nodes nid])]
                :when model-node
                :let [private? (get-in model-node [:data :private?] false)
                      parent-id (:parent model-node)]
                :when (and private?
                           parent-id
                           (not (contains? expanded parent-id)))]
            {:violation :private-visibility
             :node-id nid
             :parent-id parent-id
             :reason "private node visible but parent not expanded"}))
        true)))

;; ---------------------------------------------------------------------------
;; NoBoundingBox: leaf views show related entities from anywhere.

(defn leaf-shows-all-related?
  "Verify that for a leaf view, all directly related entities appear.
   Checks that for each visible raw edge involving the view entity, the other
   endpoint appears somewhere in the projection. Respects visible-edge-types."
  [model opts projection]
  (let [view-id (:view-id opts)
        visible-edge-types (or (:visible-edge-types opts) #{:code-flow :schema-reference})
        visible-model-kinds (set (keep {:code-flow :function-call
                                        :schema-reference :schema-reference}
                                       visible-edge-types))]
    (if (is-leaf-view? model view-id)
      (let [visible-ids (projection-node-ids projection)
            raw-edges (->> (:edges model)
                           (filter #(contains? visible-model-kinds (:kind %))))
            related-ids (->> raw-edges
                             (keep (fn [{:keys [from to]}]
                                     (cond
                                       (= from view-id) to
                                       (= to view-id) from
                                       :else nil)))
                             set)]
        (or (first
              (for [rid related-ids
                    :when (and (contains? (:nodes model) rid)
                               (not (contains? visible-ids rid)))]
                {:violation :leaf-shows-all-related
                 :missing-id rid
                 :view-id view-id}))
            true))
      true)))

;; ---------------------------------------------------------------------------
;; NoDuplicateEdges: no two edges share the same from+to+edge-type triple.

(defn no-duplicate-edges?
  "Verify that no two edges share the same (from, to, edge-type) triple."
  [_model _opts projection]
  (let [edge-keys (map (fn [{:keys [from to edge-type]}]
                          [from to edge-type])
                        (:edges projection))
        dupes (filter (fn [[_ cnt]] (> cnt 1)) (frequencies edge-keys))]
    (if (seq dupes)
      {:violation :no-duplicate-edges
       :duplicates (vec (take 5 dupes))}
      true)))

;; ---------------------------------------------------------------------------
;; VisibleNodeEdges: every code-flow edge endpoint is a visible node.

(defn visible-node-edges?
  "Verify that all edge endpoints are visible nodes in the projection."
  [_model _opts projection]
  (let [visible-ids (projection-node-ids projection)]
    (or (first
          (for [{:keys [from to edge-type]} (:edges projection)
                :when (or (not (contains? visible-ids from))
                          (not (contains? visible-ids to)))]
            {:violation :visible-node-edges
             :from from :to to :edge-type edge-type
             :from-visible? (contains? visible-ids from)
             :to-visible? (contains? visible-ids to)}))
        true)))

;; ---------------------------------------------------------------------------
;; ShowingPrivateConsistent: showing-private? implies expanded? and has-private-children?.

(defn showing-private-consistent?
  "Verify that if a node has showing-private? true, it also has expanded? and has-private-children? true."
  [_model _opts projection]
  (or (first
        (for [node (:nodes projection)
              :when (:showing-private? node)
              :when (or (not (:expanded? node))
                        (not (:has-private-children? node)))]
          {:violation :showing-private-consistent
           :node-id (:id node)
           :expanded? (:expanded? node)
           :has-private-children? (:has-private-children? node)
           :reason "showing-private? requires expanded? and has-private-children?"}))
      true))

;; ---------------------------------------------------------------------------
;; NoSubsumedEdges: no module-level edge when a child already represents it.

(defn no-subsumed-edges?
  "Verify that no edge A→T exists when a visible descendant D of A has D→T.
   The more specific edge subsumes the less specific one.
   Checked independently per edge type."
  [model _opts projection]
  (let [edges (:edges projection)]
    (or (first
          (for [edge-type (distinct (map :edge-type edges))
                :let [typed-edges (filter #(= edge-type (:edge-type %)) edges)
                      by-target (group-by :to typed-edges)]
                [target edges-to-target] by-target
                :let [sources (set (map :from edges-to-target))]
                source sources
                :when (some #(and (not= % source)
                                  (descendant-of? model % source))
                            sources)]
            {:violation :no-subsumed-edges
             :edge-type edge-type
             :subsumed-from source
             :subsumed-by (first (filter #(and (not= % source)
                                               (descendant-of? model % source))
                                         sources))
             :to target
             :reason "module-level edge subsumed by more specific descendant edge"}))
        true)))

;; ---------------------------------------------------------------------------
;; Composites

(defn valid-module-projection?
  "Run all module-view invariants."
  [model opts projection]
  (let [checks [strict-bounding-box?
                no-ancestor-descendant-edges?
                no-subsumed-edges?
                visible-node-edges?
                private-visibility?
                showing-private-consistent?
                no-duplicate-edges?]]
    (reduce (fn [_ check]
              (let [result (check model opts projection)]
                (if (true? result)
                  true
                  (reduced result))))
            true
            checks)))

(defn valid-leaf-projection?
  "Run all leaf-view invariants."
  [model opts projection]
  (let [checks [leaf-shows-all-related?
                no-ancestor-descendant-edges?
                no-duplicate-edges?]]
    (reduce (fn [_ check]
              (let [result (check model opts projection)]
                (if (true? result)
                  true
                  (reduced result))))
            true
            checks)))
