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
  "Verify that only entities inside the viewed module appear.
   Synthetic IO nodes (io-container, io-schema) are excluded from check."
  [model opts projection]
  (let [view-id (:view-id opts)
        real-node-ids (into #{} (comp (remove #(#{:io-container :io-schema} (:kind %)))
                                      (map :id))
                            (:nodes projection))]
    (or (first
          (for [nid real-node-ids
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
              :when (= :code-flow edge-type)
              :when (or (descendant-of? model to from)
                        (descendant-of? model from to))]
          {:violation :no-ancestor-descendant-edges
           :from from
           :to to}))
      true))

;; ---------------------------------------------------------------------------
;; SchemaFiltering: schema nodes are excluded from visible children.

(defn schema-filtering?
  "Verify that model :schema nodes are not directly visible as children.
   Schema nodes should only appear as synthetic IO schema nodes."
  [model opts projection]
  (let [view-id (:view-id opts)
        visible-ids (projection-node-ids projection)]
    (or (first
          (for [nid visible-ids
                :let [model-node (get-in model [:nodes nid])]
                :when (and model-node
                           (= :schema (:kind model-node))
                           (descendant-of? model nid view-id))]
            {:violation :schema-filtering
             :node-id nid
             :reason "model schema node should not appear in module view"}))
        true)))

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
;; NoIO: leaf views never produce IO boundary data.

(defn no-io-for-leaf?
  "Verify that leaf views produce no IO boundary."
  [model opts projection]
  (let [view-id (:view-id opts)]
    (if (and (is-leaf-view? model view-id)
             (or (seq (:inputs (:io projection)))
                 (seq (:outputs (:io projection)))))
      {:violation :no-io-for-leaf
       :view-id view-id
       :io (:io projection)}
      true)))

;; ---------------------------------------------------------------------------
;; NoBoundingBox: leaf views show related entities from anywhere.

(defn leaf-shows-all-related?
  "Verify that for a leaf view, all directly related entities appear.
   Checks that for each raw edge involving the view entity, the other
   endpoint appears somewhere in the projection."
  [model opts projection]
  (let [view-id (:view-id opts)]
    (if (is-leaf-view? model view-id)
      (let [visible-ids (projection-node-ids projection)
            raw-edges (:edges model)
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
;; LeafToLeafEdges: every code-flow edge connects two leaf nodes.

(defn leaf-to-leaf-edges?
  "Verify that all code-flow edges connect leaf nodes (no children)."
  [model _opts projection]
  (or (first
        (for [{:keys [from to edge-type]} (:edges projection)
              :when (= :code-flow edge-type)
              :let [from-node (get-in model [:nodes from])
                    to-node (get-in model [:nodes to])]
              :when (or (nil? from-node) (seq (:children from-node))
                        (nil? to-node) (seq (:children to-node)))]
          {:violation :leaf-to-leaf-edges
           :from from :to to
           :from-kind (:kind from-node) :to-kind (:kind to-node)}))
      true))

;; ---------------------------------------------------------------------------
;; Composites

(defn valid-module-projection?
  "Run all module-view invariants."
  [model opts projection]
  (let [checks [strict-bounding-box?
                no-ancestor-descendant-edges?
                leaf-to-leaf-edges?
                schema-filtering?
                private-visibility?
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
  (let [checks [no-io-for-leaf?
                leaf-shows-all-related?
                no-ancestor-descendant-edges?
                no-duplicate-edges?]]
    (reduce (fn [_ check]
              (let [result (check model opts projection)]
                (if (true? result)
                  true
                  (reduced result))))
            true
            checks)))
