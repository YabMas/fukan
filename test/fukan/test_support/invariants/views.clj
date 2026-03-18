(ns fukan.test-support.invariants.views
  "View invariant predicates derived from views.allium.
   Each predicate returns true on success or a descriptive map on violation.

   CytoscapeGraph format (flat maps, not nested):
   - nodes: [{:id :kind :label :selected :expandable ...}]
   - edges: [{:id :source :target :edgeType}]
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
;; RegularHighlighting: non-schema node → edges highlighted by from/to match.

(defn regular-highlighting?
  "Verify that highlighted edges are those where the selected node
   is either source or target."
  [cytoscape-graph selected-id]
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
       :actual highlighted-ids})))

;; ---------------------------------------------------------------------------
;; SchemaKeyHighlighting: schema node → edges highlighted by schema-key match.

(defn schema-key-highlighting?
  "Verify that when a schema node is selected, highlighted edges are those
   where either endpoint's schemaKey matches the selected node's schemaKey."
  [cytoscape-graph selected-id]
  (let [nodes-by-id (into {} (map (fn [n] [(:id n) n])) (:nodes cytoscape-graph))
        selected-node (get nodes-by-id selected-id)
        highlighted-ids (set (:highlightedEdges cytoscape-graph))]
    (if (and selected-node (= "schema" (:kind selected-node)))
      (let [sk (:schemaKey selected-node)
            matching-edge-ids (->> (:edges cytoscape-graph)
                                   (keep (fn [e]
                                           (when (or (= sk (:schemaKey (get nodes-by-id (:source e))))
                                                     (= sk (:schemaKey (get nodes-by-id (:target e)))))
                                             (:id e))))
                                   set)]
        (if (= highlighted-ids matching-edge-ids)
          true
          {:violation :schema-key-highlighting
           :selected-id selected-id
           :schema-key sk
           :expected matching-edge-ids
           :actual highlighted-ids}))
      ;; Not a schema node — this invariant doesn't apply
      true)))

;; ---------------------------------------------------------------------------
;; ShowingPrivateConsistent: showingPrivate → isExpanded ∧ hasPrivateChildren.

(defn showing-private-consistent?
  "Verify that if a CytoscapeNode has showingPrivate true, it also has isExpanded and hasPrivateChildren true."
  [cytoscape-graph]
  (or (first
        (for [node (:nodes cytoscape-graph)
              :when (:showingPrivate node)
              :when (or (not (:isExpanded node))
                        (not (:hasPrivateChildren node)))]
          {:violation :showing-private-consistent
           :node-id (:id node)
           :isExpanded (:isExpanded node)
           :hasPrivateChildren (:hasPrivateChildren node)
           :reason "showingPrivate requires isExpanded and hasPrivateChildren"}))
      true))

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
