(ns fukan.test-support.invariants.views
  "View invariant predicates derived from views.allium.
   Each predicate returns true on success or a descriptive map on violation.

   CytoscapeGraph format (flat maps, not nested):
   - nodes: [{:id :kind :label :selected :expandable ...}]
   - edges: [{:id :source :target :edgeType :schemaKey?}]
   - selectedId, highlightedEdges")

;; ---------------------------------------------------------------------------
;; SelectionDefault: when no explicit selection, defaults to view-id then root.

(defn selection-default?
  "Verify that exactly one node is selected, and when selected-id is nil
   the selection defaults to view-id (or root if view-id is also nil)."
  [cytoscape-graph editor-state]
  (let [selected-nodes (->> (:nodes cytoscape-graph)
                             (filter :selected))
        expected-id (or (:selected-id editor-state)
                        (:view-id editor-state))]
    (cond
      (not= 1 (count selected-nodes))
      {:violation :selection-default
       :selected-count (count selected-nodes)
       :reason "expected exactly one selected node"}

      (and expected-id
           (not= expected-id (:id (first selected-nodes))))
      {:violation :selection-default
       :expected expected-id
       :actual (:id (first selected-nodes))
       :reason "wrong node selected"}

      :else true)))

;; ---------------------------------------------------------------------------
;; SchemaKeyHighlighting: schema node → edges highlighted by schema-key match.

(defn schema-key-highlighting?
  "Verify that when selected node is a schema node, highlighted edges
   are those with matching schema-key."
  [cytoscape-graph selected-id]
  (let [highlighted-ids (set (:highlightedEdges cytoscape-graph))
        schema-prefixes ["input-schema:" "output-schema:" "internal-schema:" "schema:"]
        schema-prefix (some (fn [p] (when (.startsWith (or selected-id "") p) p))
                            schema-prefixes)]
    (if-not schema-prefix
      true
      (let [schema-key-str (subs selected-id (count schema-prefix))
            schema-key (keyword schema-key-str)
            matching-edge-ids (->> (:edges cytoscape-graph)
                                    (keep (fn [e]
                                            (when (= (name schema-key) (:schemaKey e))
                                              (:id e))))
                                    set)]
        (if (= highlighted-ids matching-edge-ids)
          true
          {:violation :schema-key-highlighting
           :selected-id selected-id
           :schema-key schema-key
           :expected matching-edge-ids
           :actual highlighted-ids})))))

;; ---------------------------------------------------------------------------
;; RegularHighlighting: non-schema node → edges highlighted by from/to match.

(defn regular-highlighting?
  "Verify that for non-schema nodes, highlighted edges are those where
   the node is either source or target."
  [cytoscape-graph selected-id]
  (let [schema-prefixes ["input-schema:" "output-schema:" "internal-schema:" "schema:"]
        is-schema? (some (fn [p] (.startsWith (or selected-id "") p)) schema-prefixes)]
    (if is-schema?
      true
      (let [highlighted-ids (set (:highlightedEdges cytoscape-graph))
            matching-edge-ids (->> (:edges cytoscape-graph)
                                    (keep (fn [e]
                                            (when (or (= selected-id (:source e))
                                                      (= selected-id (:target e)))
                                              (:id e))))
                                    set)]
        (if (= highlighted-ids matching-edge-ids)
          true
          {:violation :regular-highlighting
           :selected-id selected-id
           :expected matching-edge-ids
           :actual highlighted-ids})))))

;; ---------------------------------------------------------------------------
;; RenderPure: same input → same output.

(defn render-pure?
  "Verify that calling render twice with the same input produces identical output."
  [render-fn graph-projection editor-state]
  (let [result1 (render-fn graph-projection editor-state)
        result2 (render-fn graph-projection editor-state)]
    (if (= result1 result2)
      true
      {:violation :render-pure
       :reason "render produced different output for same input"})))
