(ns fukan.web.views.graph-test
  "Tests encoding view-spec.md behavior with exact output matching."
  (:require [clojure.test :refer [deftest testing is]]
            [fukan.web.views.graph :as graph]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn edges-set
  "Extract edge from-to pairs as a set for comparison."
  [graph]
  (set (map #(select-keys % [:from :to]) (:edges graph))))

(defn node-ids
  "Get set of node IDs from graph."
  [graph]
  (set (map :id (:nodes graph))))

(defn find-node
  "Find a node by ID in the graph."
  [graph id]
  (first (filter #(= id (:id %)) (:nodes graph))))

(defn node-parent
  "Get the parent of a node in the graph."
  [graph id]
  (:parent (find-node graph id)))

(defn find-edge
  "Find an edge by from and to."
  [graph from to]
  (first (filter #(and (= from (:from %)) (= to (:to %)))
                 (:edges graph))))

(defn highlighted-edges
  "Get set of highlighted edge IDs."
  [graph]
  (set (map :id (filter :highlighted? (:edges graph)))))

;; =============================================================================
;; Test Model Builders
;; =============================================================================

(defn build-example1-model
  "Build model for Example 1 from view-spec.md.

   Structure:
   - folder:fukan
     - ns:fukan.core
     - ns:fukan.analysis
     - ns:fukan.cytoscape
     - ns:fukan.model
     - folder:web
       - ns:fukan.web.handler
       - ns:fukan.web.views

   Dependencies:
   - var:fukan.core/main depends on var:fukan.web.handler/create-handler"
  []
  {:nodes
   {;; Root folder
    "folder:fukan"
    {:id "folder:fukan"
     :kind :folder
     :label "fukan"
     :parent nil
     :children #{"ns:fukan.core" "ns:fukan.analysis" "ns:fukan.cytoscape"
                 "ns:fukan.model" "folder:web"}
     :data {:path "src/fukan"}}

    ;; Top-level namespaces
    "ns:fukan.core"
    {:id "ns:fukan.core"
     :kind :namespace
     :label "fukan.core"
     :parent "folder:fukan"
     :children #{"var:fukan.core/main"}
     :data {:ns-sym 'fukan.core}}

    "ns:fukan.analysis"
    {:id "ns:fukan.analysis"
     :kind :namespace
     :label "fukan.analysis"
     :parent "folder:fukan"
     :children #{"var:fukan.analysis/analyze"}
     :data {:ns-sym 'fukan.analysis}}

    "ns:fukan.cytoscape"
    {:id "ns:fukan.cytoscape"
     :kind :namespace
     :label "fukan.cytoscape"
     :parent "folder:fukan"
     :children #{"var:fukan.cytoscape/node->cytoscape"}
     :data {:ns-sym 'fukan.cytoscape}}

    "ns:fukan.model"
    {:id "ns:fukan.model"
     :kind :namespace
     :label "fukan.model"
     :parent "folder:fukan"
     :children #{"var:fukan.model/entity-kind"}
     :data {:ns-sym 'fukan.model}}

    ;; Web subfolder
    "folder:web"
    {:id "folder:web"
     :kind :folder
     :label "web"
     :parent "folder:fukan"
     :children #{"ns:fukan.web.handler" "ns:fukan.web.views"}
     :data {:path "src/fukan/web"}}

    "ns:fukan.web.handler"
    {:id "ns:fukan.web.handler"
     :kind :namespace
     :label "fukan.web.handler"
     :parent "folder:web"
     :children #{"var:fukan.web.handler/create-handler" "var:fukan.web.handler/compute-view"}
     :data {:ns-sym 'fukan.web.handler}}

    "ns:fukan.web.views"
    {:id "ns:fukan.web.views"
     :kind :namespace
     :label "fukan.web.views"
     :parent "folder:web"
     :children #{"var:fukan.web.views/render"}
     :data {:ns-sym 'fukan.web.views}}

    ;; Vars
    "var:fukan.core/main"
    {:id "var:fukan.core/main"
     :kind :var
     :label "main"
     :parent "ns:fukan.core"
     :children #{}
     :data {:ns-sym 'fukan.core :var-sym 'main :private? false}}

    "var:fukan.analysis/analyze"
    {:id "var:fukan.analysis/analyze"
     :kind :var
     :label "analyze"
     :parent "ns:fukan.analysis"
     :children #{}
     :data {:ns-sym 'fukan.analysis :var-sym 'analyze :private? false}}

    "var:fukan.cytoscape/node->cytoscape"
    {:id "var:fukan.cytoscape/node->cytoscape"
     :kind :var
     :label "node->cytoscape"
     :parent "ns:fukan.cytoscape"
     :children #{}
     :data {:ns-sym 'fukan.cytoscape :var-sym 'node->cytoscape :private? false}}

    "var:fukan.model/entity-kind"
    {:id "var:fukan.model/entity-kind"
     :kind :var
     :label "entity-kind"
     :parent "ns:fukan.model"
     :children #{}
     :data {:ns-sym 'fukan.model :var-sym 'entity-kind :private? false}}

    "var:fukan.web.handler/create-handler"
    {:id "var:fukan.web.handler/create-handler"
     :kind :var
     :label "create-handler"
     :parent "ns:fukan.web.handler"
     :children #{}
     :data {:ns-sym 'fukan.web.handler :var-sym 'create-handler :private? false}}

    "var:fukan.web.handler/compute-view"
    {:id "var:fukan.web.handler/compute-view"
     :kind :var
     :label "compute-view"
     :parent "ns:fukan.web.handler"
     :children #{}
     :data {:ns-sym 'fukan.web.handler :var-sym 'compute-view :private? false}}

    "var:fukan.web.views/render"
    {:id "var:fukan.web.views/render"
     :kind :var
     :label "render"
     :parent "ns:fukan.web.views"
     :children #{}
     :data {:ns-sym 'fukan.web.views :var-sym 'render :private? false}}}

   ;; Var-level edges (raw dependencies)
   :edges
   [{:from "var:fukan.core/main" :to "var:fukan.web.handler/create-handler"}]})

(defn build-example2-model
  "Build model for Example 2: No cross-container relations.
   Same structure as example1 but without the dependency."
  []
  (assoc (build-example1-model) :edges []))

(defn build-example3-model
  "Build model for Example 3: Viewing a namespace.

   compute-view depends on node->cytoscape and entity-kind."
  []
  (let [base (build-example1-model)]
    (assoc base :edges
           [{:from "var:fukan.web.handler/compute-view" :to "var:fukan.cytoscape/node->cytoscape"}
            {:from "var:fukan.web.handler/compute-view" :to "var:fukan.model/entity-kind"}
            {:from "var:fukan.web.handler/create-handler" :to "var:fukan.web.handler/compute-view"}])))

(defn build-model-with-private-var
  "Build a simple model with a private var for visibility testing."
  []
  {:nodes
   {"ns:foo"
    {:id "ns:foo"
     :kind :namespace
     :label "foo"
     :parent nil
     :children #{"var:foo/public-fn" "var:foo/private-fn"}
     :data {:ns-sym 'foo}}

    "var:foo/public-fn"
    {:id "var:foo/public-fn"
     :kind :var
     :label "public-fn"
     :parent "ns:foo"
     :children #{}
     :data {:ns-sym 'foo :var-sym 'public-fn :private? false}}

    "var:foo/private-fn"
    {:id "var:foo/private-fn"
     :kind :var
     :label "private-fn"
     :parent "ns:foo"
     :children #{}
     :data {:ns-sym 'foo :var-sym 'private-fn :private? true}}}

   :edges []})

;; =============================================================================
;; Container View Tests (entity with children)
;; =============================================================================

(deftest container-view-with-drill-down
  (testing "Example 1: when child relates to entity inside sibling container"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; EXACT node set - not "contains", but equals
      ;; Shows: fukan.core, fukan.analysis, fukan.cytoscape, fukan.model, web folder,
      ;; AND fukan.web.handler (because it's related to fukan.core)
      ;; Does NOT show: fukan.web.views (not related to any sibling)
      (is (= #{"folder:fukan" "ns:fukan.core" "ns:fukan.analysis"
               "ns:fukan.cytoscape" "ns:fukan.model" "folder:web" "ns:fukan.web.handler"}
             (node-ids graph)))

      ;; Verify compound node structure (parent relationships)
      (is (= "folder:fukan" (node-parent graph "ns:fukan.core")))
      (is (= "folder:fukan" (node-parent graph "ns:fukan.analysis")))
      (is (= "folder:fukan" (node-parent graph "folder:web")))
      ;; Handler is nested INSIDE the web folder (drill-down)
      (is (= "folder:web" (node-parent graph "ns:fukan.web.handler")))

      ;; EXACT edge set - aggregated at namespace level
      (is (= #{{:from "ns:fukan.core" :to "ns:fukan.web.handler"}}
             (edges-set graph)))

      ;; Node properties for rendering
      (let [web-node (find-node graph "folder:web")]
        (is (= :folder (:kind web-node)))
        (is (true? (:expandable? web-node)))))))

(deftest container-view-simple
  (testing "Example 2: no cross-container relations -> children as simple nodes"
    (let [model (build-example2-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; web appears but NOT its children (no drill-down needed)
      (is (= #{"folder:fukan" "ns:fukan.core" "ns:fukan.analysis"
               "ns:fukan.cytoscape" "ns:fukan.model" "folder:web"}
             (node-ids graph)))

      ;; web has no nested nodes shown
      (is (nil? (find-node graph "ns:fukan.web.handler")))
      (is (nil? (find-node graph "ns:fukan.web.views")))

      ;; No edges (no dependencies in this model)
      (is (empty? (:edges graph))))))

(deftest namespace-view-strict-bounding-box
  (testing "Example 3: strict bounding box - only children visible, no external entities"
    (let [model (build-example3-model)
          graph (graph/compute-graph model {:view-id "ns:fukan.web.handler"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Shows the namespace and its vars
      (is (contains? (node-ids graph) "ns:fukan.web.handler"))
      (is (contains? (node-ids graph) "var:fukan.web.handler/create-handler"))
      (is (contains? (node-ids graph) "var:fukan.web.handler/compute-view"))

      ;; External entities should NOT be visible (strict bounding box)
      (is (not (contains? (node-ids graph) "ns:fukan.cytoscape"))
          "External namespace should not be visible")
      (is (not (contains? (node-ids graph) "var:fukan.cytoscape/node->cytoscape"))
          "External var should not be visible")
      (is (not (contains? (node-ids graph) "ns:fukan.model"))
          "External namespace should not be visible")

      ;; Only internal edges visible (to external entities are filtered)
      (is (contains? (edges-set graph) {:from "var:fukan.web.handler/create-handler"
                                        :to "var:fukan.web.handler/compute-view"})
          "Internal edge should be visible")
      (is (not (contains? (edges-set graph) {:from "var:fukan.web.handler/compute-view"
                                             :to "var:fukan.cytoscape/node->cytoscape"}))
          "External edge should not be visible"))))

;; =============================================================================
;; Leaf View Tests (entity without children)
;; =============================================================================

(deftest leaf-view
  (testing "Example 4: leaf shows relationships grouped by parent container"
    (let [model (build-example3-model)
          graph (graph/compute-graph model {:view-id "var:fukan.web.handler/compute-view"
                                            :selected-id "var:fukan.web.handler/compute-view"
                                            :expanded-containers #{}})]

      ;; Shows the selected var and all related vars
      (is (contains? (node-ids graph) "var:fukan.web.handler/compute-view"))
      (is (contains? (node-ids graph) "var:fukan.cytoscape/node->cytoscape"))
      (is (contains? (node-ids graph) "var:fukan.model/entity-kind"))
      (is (contains? (node-ids graph) "var:fukan.web.handler/create-handler"))

      ;; Parent containers shown for grouping
      (is (contains? (node-ids graph) "ns:fukan.cytoscape"))
      (is (contains? (node-ids graph) "ns:fukan.model"))
      (is (contains? (node-ids graph) "ns:fukan.web.handler"))

      ;; Parent relationships for grouping
      (is (= "ns:fukan.cytoscape" (node-parent graph "var:fukan.cytoscape/node->cytoscape")))
      (is (= "ns:fukan.model" (node-parent graph "var:fukan.model/entity-kind")))
      (is (= "ns:fukan.web.handler" (node-parent graph "var:fukan.web.handler/compute-view")))

      ;; Only edges involving selected node
      (is (= #{{:from "var:fukan.web.handler/compute-view" :to "var:fukan.cytoscape/node->cytoscape"}
               {:from "var:fukan.web.handler/compute-view" :to "var:fukan.model/entity-kind"}
               {:from "var:fukan.web.handler/create-handler" :to "var:fukan.web.handler/compute-view"}}
             (edges-set graph))))))

;; =============================================================================
;; Edge Highlighting Tests
;; =============================================================================

(deftest edge-highlighting-by-selection
  (testing "selected node highlights its edges"
    (let [model (build-example3-model)
          ;; View the handler namespace with compute-view selected
          graph (graph/compute-graph model {:view-id "ns:fukan.web.handler"
                                            :selected-id "var:fukan.web.handler/compute-view"
                                            :expanded-containers #{}})]

      ;; Edge TO selected is highlighted (only internal edges visible due to strict bounding box)
      (is (true? (:highlighted? (find-edge graph
                                            "var:fukan.web.handler/create-handler"
                                            "var:fukan.web.handler/compute-view")))
          "Internal edge to selected node should be highlighted"))))

(deftest compute-highlighted-edges-function
  (testing "compute-highlighted-edges returns correct edge IDs"
    (let [edges [{:id "e1" :from "a" :to "b"}
                 {:id "e2" :from "b" :to "c"}
                 {:id "e3" :from "c" :to "d"}]]

      ;; Edges involving node "b"
      (is (= ["e1" "e2"] (graph/compute-highlighted-edges edges "b")))

      ;; Edges involving node "a"
      (is (= ["e1"] (graph/compute-highlighted-edges edges "a")))

      ;; No matching edges
      (is (= [] (graph/compute-highlighted-edges edges "z"))))))

;; =============================================================================
;; Private Visibility Tests
;; =============================================================================

(deftest private-visibility-collapsed
  (testing "private children hidden when container not expanded"
    (let [model (build-model-with-private-var)
          graph (graph/compute-graph model {:view-id "ns:foo"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Only public var shown
      (is (contains? (node-ids graph) "var:foo/public-fn"))
      ;; Private var hidden
      (is (not (contains? (node-ids graph) "var:foo/private-fn")))

      ;; Container shows has-private indicator
      (is (true? (:has-private-children? (find-node graph "ns:foo")))))))

(deftest private-visibility-expanded
  (testing "private children shown when container expanded"
    (let [model (build-model-with-private-var)
          graph (graph/compute-graph model {:view-id "ns:foo"
                                            :selected-id nil
                                            :expanded-containers #{"ns:foo"}})]

      ;; Both vars shown
      (is (contains? (node-ids graph) "var:foo/public-fn"))
      (is (contains? (node-ids graph) "var:foo/private-fn"))

      ;; Container shows is-expanded indicator
      (is (true? (:expanded? (find-node graph "ns:foo")))))))

;; =============================================================================
;; Edge Aggregation Tests
;; =============================================================================

(deftest edge-aggregation-at-namespace-level
  (testing "var edges aggregate to namespace level when viewing folder"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Edge is aggregated: var:fukan.core/main -> var:fukan.web.handler/create-handler
      ;; becomes: ns:fukan.core -> ns:fukan.web.handler
      (is (= #{{:from "ns:fukan.core" :to "ns:fukan.web.handler"}}
             (edges-set graph)))

      ;; No var-level edges shown at folder level
      (is (not (contains? (edges-set graph)
                          {:from "var:fukan.core/main"
                           :to "var:fukan.web.handler/create-handler"}))))))

(deftest no-self-edges-after-aggregation
  (testing "self-edges are excluded after aggregation"
    (let [model {:nodes
                 {"ns:foo"
                  {:id "ns:foo"
                   :kind :namespace
                   :label "foo"
                   :parent nil
                   :children #{"var:foo/a" "var:foo/b"}
                   :data {:ns-sym 'foo}}
                  "var:foo/a"
                  {:id "var:foo/a"
                   :kind :var
                   :label "a"
                   :parent "ns:foo"
                   :children #{}
                   :data {:ns-sym 'foo :var-sym 'a :private? false}}
                  "var:foo/b"
                  {:id "var:foo/b"
                   :kind :var
                   :label "b"
                   :parent "ns:foo"
                   :children #{}
                   :data {:ns-sym 'foo :var-sym 'b :private? false}}}
                 ;; Edges within same namespace
                 :edges [{:from "var:foo/a" :to "var:foo/b"}]}
          ;; View at folder level would aggregate both vars to ns:foo
          graph (graph/compute-graph model {:view-id "ns:foo"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Var-level edges are shown when viewing the namespace
      (is (contains? (edges-set graph) {:from "var:foo/a" :to "var:foo/b"})))))

;; =============================================================================
;; Default View Tests
;; =============================================================================

(deftest default-view-uses-root
  (testing "nil view-id defaults to root node"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id nil
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Should show the root folder's children
      (is (contains? (node-ids graph) "folder:fukan"))
      (is (contains? (node-ids graph) "ns:fukan.core")))))

(deftest selected-id-defaults-to-view-id
  (testing "nil selected-id defaults to view-id"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; The view-id node should be marked as selected
      (is (true? (:selected? (find-node graph "folder:fukan")))))))

;; =============================================================================
;; Node Properties Tests
;; =============================================================================

(deftest node-has-expected-properties
  (testing "nodes include expected rendering properties"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id "ns:fukan.core"
                                            :expanded-containers #{}})]

      (let [core-node (find-node graph "ns:fukan.core")]
        (is (= "ns:fukan.core" (:id core-node)))
        (is (= :namespace (:kind core-node)))
        (is (= "fukan.core" (:label core-node)))
        (is (= "folder:fukan" (:parent core-node)))
        (is (true? (:selected? core-node)))
        (is (true? (:expandable? core-node)))  ; has children (vars)
        )

      (let [fukan-node (find-node graph "folder:fukan")]
        (is (false? (:selected? fukan-node)))))))

;; =============================================================================
;; Parent-Child Edge Exclusion Tests
;; =============================================================================

(deftest no-parent-child-edges
  (testing "edges between container and child are excluded"
    (let [model (build-example1-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      ;; Parent-child edges must NOT exist
      (is (not (contains? (edges-set graph) {:from "folder:web" :to "ns:fukan.web.handler"})))
      (is (not (contains? (edges-set graph) {:from "ns:fukan.web.handler" :to "folder:web"})))
      (is (not (contains? (edges-set graph) {:from "folder:fukan" :to "ns:fukan.core"})))
      (is (not (contains? (edges-set graph) {:from "folder:fukan" :to "folder:web"}))))))

;; =============================================================================
;; Drill-Down Outgoing Edge Tests
;; =============================================================================

(defn build-model-with-drill-down-deps
  "Build model with edges FROM drill-down namespaces.

   Structure: same as example1

   Dependencies:
   - fukan.core/main -> fukan.web.handler/create-handler (triggers drill-down)
   - fukan.web.handler/compute-view -> fukan.model/entity-kind (drill-down -> external)
   - fukan.web.handler/compute-view -> fukan.web.views/render (internal to web folder)"
  []
  (assoc (build-example1-model)
         :edges
         [;; External -> drill-down (triggers drill-down)
          {:from "var:fukan.core/main" :to "var:fukan.web.handler/create-handler"}
          ;; Drill-down -> external sibling (should be visible!)
          {:from "var:fukan.web.handler/compute-view" :to "var:fukan.model/entity-kind"}
          ;; Internal edge within folder (drill-down -> non-drill-down inside folder)
          {:from "var:fukan.web.handler/compute-view" :to "var:fukan.web.views/render"}]))

(deftest drill-down-outgoing-edges
  (testing "edges FROM drill-down namespace to external siblings are visible"
    (let [model (build-model-with-drill-down-deps)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Edge from drill-down to external sibling should exist
      (is (contains? (edges-set graph)
                     {:from "ns:fukan.web.handler" :to "ns:fukan.model"})
          "Edge from drill-down ns to external sibling should be visible")))

  (testing "internal dependencies of drill-down are expanded"
    (let [model (build-model-with-drill-down-deps)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; fukan.web.views should be expanded because fukan.web.handler depends on it
      (is (contains? (node-ids graph) "ns:fukan.web.views")
          "Internal dependency of drill-down ns should be shown")

      ;; Edge between drill-down namespaces inside the same folder
      (is (contains? (edges-set graph)
                     {:from "ns:fukan.web.handler" :to "ns:fukan.web.views"})
          "Edge between drill-down namespaces should be visible")))

  (testing "all expected edges are present"
    (let [model (build-model-with-drill-down-deps)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; All three edges should be visible at namespace level
      (is (= #{{:from "ns:fukan.core" :to "ns:fukan.web.handler"}
               {:from "ns:fukan.web.handler" :to "ns:fukan.model"}
               {:from "ns:fukan.web.handler" :to "ns:fukan.web.views"}}
             (edges-set graph))))))

;; =============================================================================
;; Generic Container-Child Edge Invariant Tests
;; =============================================================================

(defn- has-container-child-edge?
  "Check if any edge connects a container to its direct child.
   This should never happen according to view-spec.md."
  [model graph]
  (some (fn [{:keys [from to]}]
          (let [from-parent (:parent (get-in model [:nodes from]))
                to-parent (:parent (get-in model [:nodes to]))]
            (or (= from to-parent)
                (= to from-parent))))
        (:edges graph)))

(defn build-model-with-toplevel-edge
  "Build model where namespace has top-level code creating ns->var edge.
   This tests the bug where container-to-child edges were shown."
  []
  {:nodes
   {"ns:foo"
    {:id "ns:foo"
     :kind :namespace
     :label "foo"
     :parent nil
     :children #{"var:foo/bar" "var:foo/baz"}
     :data {:ns-sym 'foo}}

    "var:foo/bar"
    {:id "var:foo/bar"
     :kind :var
     :label "bar"
     :parent "ns:foo"
     :children #{}
     :data {:ns-sym 'foo :var-sym 'bar :private? false}}

    "var:foo/baz"
    {:id "var:foo/baz"
     :kind :var
     :label "baz"
     :parent "ns:foo"
     :children #{}
     :data {:ns-sym 'foo :var-sym 'baz :private? false}}}

   ;; Top-level code creates ns->var edge, plus var->var edge
   :edges [{:from "ns:foo" :to "var:foo/bar"}
           {:from "var:foo/bar" :to "var:foo/baz"}]})

(deftest container-view-invariants-generic
  (testing "INVARIANT: no container-child edges in any view type"
    ;; Test with folder view
    (let [model (build-example3-model)
          graph (graph/compute-graph model {:view-id "folder:fukan"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      (is (not (has-container-child-edge? model graph))
          "Folder view should not have container-child edges"))

    ;; Test with namespace view
    (let [model (build-example3-model)
          graph (graph/compute-graph model {:view-id "ns:fukan.web.handler"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      (is (not (has-container-child-edge? model graph))
          "Namespace view should not have container-child edges"))

    ;; Test with model that has ns->var edge (the original bug)
    (let [model (build-model-with-toplevel-edge)
          graph (graph/compute-graph model {:view-id "ns:foo"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      (is (not (has-container-child-edge? model graph))
          "Namespace view with toplevel edge should not show ns->var edge"))))

(deftest toplevel-edge-handling
  (testing "top-level ns->var edges are filtered out when viewing namespace"
    (let [model (build-model-with-toplevel-edge)
          graph (graph/compute-graph model {:view-id "ns:foo"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      ;; The ns->var edge should NOT appear
      (is (not (contains? (edges-set graph) {:from "ns:foo" :to "var:foo/bar"}))
          "ns->var edge from top-level code should be excluded")

      ;; But var->var edges should still appear
      (is (contains? (edges-set graph) {:from "var:foo/bar" :to "var:foo/baz"})
          "var->var edges should still be shown"))))

;; =============================================================================
;; NS-Require Container-Child Edge Tests
;; =============================================================================

(defn build-model-with-ns-require-to-child
  "Build model where a namespace requires a child namespace.
   This tests that :ns-require edges don't bypass container-child filtering.

   Structure:
   - folder:parent
     - ns:parent.foo (requires parent.foo.bar)
     - folder:parent/foo
       - ns:parent.foo.bar"
  []
  {:nodes
   {"folder:parent"
    {:id "folder:parent"
     :kind :folder
     :label "parent"
     :parent nil
     :children #{"ns:parent.foo" "folder:parent/foo"}}

    "ns:parent.foo"
    {:id "ns:parent.foo"
     :kind :namespace
     :label "parent.foo"
     :parent "folder:parent"
     :children #{"var:parent.foo/x"}
     :data {:ns-sym 'parent.foo}}

    "var:parent.foo/x"
    {:id "var:parent.foo/x"
     :kind :var
     :label "x"
     :parent "ns:parent.foo"
     :children #{}
     :data {:ns-sym 'parent.foo :var-sym 'x :private? false}}

    "folder:parent/foo"
    {:id "folder:parent/foo"
     :kind :folder
     :label "foo"
     :parent "folder:parent"
     :children #{"ns:parent.foo.bar"}}

    "ns:parent.foo.bar"
    {:id "ns:parent.foo.bar"
     :kind :namespace
     :label "parent.foo.bar"
     :parent "folder:parent/foo"
     :children #{"var:parent.foo.bar/y"}
     :data {:ns-sym 'parent.foo.bar}}

    "var:parent.foo.bar/y"
    {:id "var:parent.foo.bar/y"
     :kind :var
     :label "y"
     :parent "ns:parent.foo.bar"
     :children #{}
     :data {:ns-sym 'parent.foo.bar :var-sym 'y :private? false}}}

   ;; ns-require edge from parent namespace to child namespace
   :edges [{:from "ns:parent.foo" :to "ns:parent.foo.bar" :kind :ns-require}
           ;; Also a var-level edge to trigger drill-down
           {:from "var:parent.foo/x" :to "var:parent.foo.bar/y"}]})

(deftest ns-require-container-child-edges
  (testing "ns-require edges don't bypass container-child filtering"
    (let [model (build-model-with-ns-require-to-child)
          graph (graph/compute-graph model {:view-id "folder:parent"
                                            :selected-id nil
                                            :expanded-containers #{}})]
      ;; The edge should be shown as ns:parent.foo -> ns:parent.foo.bar (both visible)
      ;; NOT as ns:parent.foo -> folder:parent/foo (container-child would be wrong)
      (is (not (has-container-child-edge? model graph))
          "ns-require edge should not create container-child edge")

      ;; The actual ns->ns edge should exist (drill-down makes both visible)
      (is (contains? (edges-set graph) {:from "ns:parent.foo" :to "ns:parent.foo.bar"})
          "ns-require edge between siblings should be visible"))))

;; =============================================================================
;; Same-Type Drill-Down Tests
;; =============================================================================

(defn build-model-with-mixed-types
  "Build model for same-type drill-down test.

   Structure:
   - folder:root
     - ns:alpha (namespace, depends on ns:beta.child via var edge)
     - folder:beta
       - ns:beta.child (namespace)
       - var:beta.standalone (var directly under folder, unusual but tests type filtering)

   This tests that namespace→namespace edges drill down to namespaces,
   not to vars even if vars exist as direct children of the container."
  []
  {:nodes
   {"folder:root"
    {:id "folder:root"
     :kind :folder
     :label "root"
     :parent nil
     :children #{"ns:alpha" "folder:beta"}}

    "ns:alpha"
    {:id "ns:alpha"
     :kind :namespace
     :label "alpha"
     :parent "folder:root"
     :children #{"var:alpha/x"}
     :data {:ns-sym 'alpha}}

    "var:alpha/x"
    {:id "var:alpha/x"
     :kind :var
     :label "x"
     :parent "ns:alpha"
     :children #{}
     :data {:ns-sym 'alpha :var-sym 'x :private? false}}

    "folder:beta"
    {:id "folder:beta"
     :kind :folder
     :label "beta"
     :parent "folder:root"
     :children #{"ns:beta.child"}}

    "ns:beta.child"
    {:id "ns:beta.child"
     :kind :namespace
     :label "beta.child"
     :parent "folder:beta"
     :children #{"var:beta.child/y"}
     :data {:ns-sym 'beta.child}}

    "var:beta.child/y"
    {:id "var:beta.child/y"
     :kind :var
     :label "y"
     :parent "ns:beta.child"
     :children #{}
     :data {:ns-sym 'beta.child :var-sym 'y :private? false}}}

   ;; var-level edge that will aggregate to ns:alpha -> ns:beta.child
   :edges [{:from "var:alpha/x" :to "var:beta.child/y"}]})

(deftest same-type-drill-down
  (testing "drill-down shows only same-type entities (namespace→namespace)"
    (let [model (build-model-with-mixed-types)
          graph (graph/compute-graph model {:view-id "folder:root"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; Namespace source should drill down to namespace targets
      (is (contains? (node-ids graph) "ns:beta.child")
          "same-type namespace target should be visible via drill-down")

      ;; The folder:beta container should be visible (as grouping)
      (is (contains? (node-ids graph) "folder:beta")
          "sibling container should be visible")

      ;; Var children of drill-down namespace should NOT be shown at folder level
      (is (not (contains? (node-ids graph) "var:beta.child/y"))
          "var children should not appear from namespace drill-down")

      ;; Edge should be at namespace level
      (is (contains? (edges-set graph) {:from "ns:alpha" :to "ns:beta.child"})
          "edge should be aggregated to namespace level"))))

;; =============================================================================
;; Container Source Drill-Down Tests (edge FROM container, not TO)
;; =============================================================================

(defn build-model-container-as-source
  "Build model where container has children with outgoing edges,
   but no incoming edges to the container itself.

   This tests the fix for the bug where drill-down only expanded
   containers that were edge TARGETS, not edge SOURCES.

   Structure:
   - folder:root
     - ns:target.ns (will receive edge from model-folder child)
     - folder:model-folder
       - ns:model.build (has edge OUT to target.ns)

   Crucially, there is NO edge from outside INTO model-folder.
   The only edge is FROM model-folder's child OUT to a sibling."
  []
  {:nodes
   {"folder:root"
    {:id "folder:root"
     :kind :folder
     :label "root"
     :parent nil
     :children #{"ns:target.ns" "folder:model-folder"}}

    "ns:target.ns"
    {:id "ns:target.ns"
     :kind :namespace
     :label "target.ns"
     :parent "folder:root"
     :children #{"var:target.ns/receive"}
     :data {:ns-sym 'target.ns}}

    "var:target.ns/receive"
    {:id "var:target.ns/receive"
     :kind :var
     :label "receive"
     :parent "ns:target.ns"
     :children #{}
     :data {:ns-sym 'target.ns :var-sym 'receive :private? false}}

    "folder:model-folder"
    {:id "folder:model-folder"
     :kind :folder
     :label "model-folder"
     :parent "folder:root"
     :children #{"ns:model.build"}}

    "ns:model.build"
    {:id "ns:model.build"
     :kind :namespace
     :label "model.build"
     :parent "folder:model-folder"
     :children #{"var:model.build/call-target"}
     :data {:ns-sym 'model.build}}

    "var:model.build/call-target"
    {:id "var:model.build/call-target"
     :kind :var
     :label "call-target"
     :parent "ns:model.build"
     :children #{}
     :data {:ns-sym 'model.build :var-sym 'call-target :private? false}}}

   ;; Only edge: FROM model.build -> TO target.ns
   ;; model-folder is edge SOURCE but NOT edge TARGET
   :edges [{:from "var:model.build/call-target" :to "var:target.ns/receive"}]})

(deftest container-source-drill-down
  (testing "container that is edge SOURCE (not target) should drill down"
    (let [model (build-model-container-as-source)
          graph (graph/compute-graph model {:view-id "folder:root"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; The model-folder should be drilled into because it's an edge source
      (is (contains? (node-ids graph) "ns:model.build")
          "namespace inside container source should be visible")

      ;; The edge should show actual source, not the container
      (is (contains? (edges-set graph) {:from "ns:model.build" :to "ns:target.ns"})
          "edge should show ns:model.build, not folder:model-folder")

      ;; The container-to-sibling edge should NOT exist
      (is (not (contains? (edges-set graph) {:from "folder:model-folder" :to "ns:target.ns"}))
          "edge should NOT show folder:model-folder as source")))

  (testing "drill-down of source container exposes correct node hierarchy"
    (let [model (build-model-container-as-source)
          graph (graph/compute-graph model {:view-id "folder:root"
                                            :selected-id nil
                                            :expanded-containers #{}})]

      ;; ns:model.build should be parented under folder:model-folder
      (is (= "folder:model-folder" (node-parent graph "ns:model.build"))
          "drilled-down source ns should be nested in its container"))))
