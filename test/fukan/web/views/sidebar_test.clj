(ns fukan.web.views.sidebar-test
  "Tests for sidebar rendering.
   Verifies edge rendering dispatch logic and section rendering."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.web.views.sidebar :as sidebar]))

;; ---------------------------------------------------------------------------
;; Edge rendering: code-flow with called-fns only

(deftest render-code-flow-called-fns-only
  (testing "code-flow edge with only called-fns renders Functions Called section"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :code-flow
                  :called-fns [{:name "foo" :id "ns:b/foo"}]
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Functions Called" html))
      (is (not (re-find #"Dispatched Functions" html)))
      (is (re-find #"foo" html)))))

;; ---------------------------------------------------------------------------
;; Edge rendering: code-flow with dispatched-fns only

(deftest render-code-flow-dispatched-fns-only
  (testing "code-flow edge with only dispatched-fns renders Dispatched Functions section"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :code-flow
                  :dispatched-fns [{:name "bar" :id "ns:b/bar"}]
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      (is (not (re-find #"Functions Called" html)))
      (is (re-find #"Dispatched Functions" html))
      (is (re-find #"bar" html)))))

;; ---------------------------------------------------------------------------
;; Edge rendering: code-flow with both (mixed edge)

(deftest render-code-flow-mixed-edge
  (testing "code-flow edge with both called-fns and dispatched-fns renders both sections"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :code-flow
                  :called-fns [{:name "foo" :id "ns:b/foo"}]
                  :dispatched-fns [{:name "bar" :id "ns:b/bar"}]
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Functions Called" html))
      (is (re-find #"Dispatched Functions" html))
      (is (re-find #"foo" html))
      (is (re-find #"bar" html)))))

;; ---------------------------------------------------------------------------
;; Edge rendering: code-flow empty state

(deftest render-code-flow-empty
  (testing "code-flow edge with no functions shows empty state"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :code-flow
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"No direct function calls" html)))))

;; ---------------------------------------------------------------------------
;; Edge rendering: schema-reference

(deftest render-schema-reference-edge
  (testing "schema-reference edge renders Schema References section"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :schema-reference
                  :schema-refs [{:from-schema {:id "ns:a/X" :label "X" :schema-key :X}
                                 :to-schema {:id "ns:b/Y" :label "Y" :schema-key :Y}}]
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Schema References" html)))))

;; ---------------------------------------------------------------------------
;; Empty sidebar

(deftest render-empty-sidebar
  (testing "no entity detail renders empty state"
    (let [html (sidebar/render-sidebar-html {})]
      (is (re-find #"Click a node to see details" html)))))

;; ---------------------------------------------------------------------------
;; Edge rendering: schema-reference includes label

(deftest render-schema-reference-edge-includes-label
  (testing "schema-reference edge renders the edge label as an h4"
    (let [detail {:label "a → b"
                  :kind :edge
                  :edge-type :schema-reference
                  :schema-refs [{:from-schema {:id "ns:a/X" :label "X" :schema-key :X}
                                 :to-schema {:id "ns:b/Y" :label "Y" :schema-key :Y}}]
                  :schema-ids {}}
          html (sidebar/render-sidebar-html detail)]
      ;; The label should appear in an h4 before the Schema References heading
      (is (re-find #"<h4>" html) "schema-reference edge should have an h4 label")
      (is (re-find #"a → b" html) "edge label text should be present")
      (let [label-pos (.indexOf html "a → b")
            refs-pos (.indexOf html "Schema References")]
        (is (< label-pos refs-pos) "label should appear before Schema References heading")))))

;; ---------------------------------------------------------------------------
;; Edge rendering: view-url used in dep-list for view state preservation

(deftest render-dep-list-preserves-view-state
  (testing "render-dep-list builds URLs with view state params like view-url"
    ;; render-dep-list is private, but we test through render-entity-detail
    ;; by checking that sidebar links include getExpandedParam etc.
    ;; We use a module with interface fn-list items that have IDs
    (let [detail {:label "MyModule"
                  :kind :module
                  :description nil
                  :interface {:type :fn-list :items [{:name "do-thing" :id "ns:a/do-thing"}]}
                  :schema-ids {}
                  :dataflow nil}
          html (sidebar/render-sidebar-html detail)]
      ;; fn-list items use view-url which includes getExpandedParam
      (is (re-find #"getExpandedParam" html)
          "clickable items should preserve expanded view state")
      (is (re-find #"getShowPrivateParam" html)
          "clickable items should preserve show_private view state")
      (is (re-find #"getVisibleEdgeTypesParam" html)
          "clickable items should preserve visible_edge_types view state"))))

;; ---------------------------------------------------------------------------
;; Entity rendering: section order

(deftest render-entity-section-order
  (testing "entity detail renders sections in spec order: label, defined-in, description, guarantees, defined types, interface"
    (let [detail {:label "MyModule"
                  :kind :module
                  :parent {:id "root" :label "root"}
                  :description "A test module"
                  :guarantees ["always works"]
                  :schemas [{:label "MySchema" :key :MySchema :id "ns:a/MySchema"}]
                  :interface {:type :fn-list :items [{:name "do-thing" :id "ns:a/do-thing"}]}
                  :schema-ids {}
                  :dataflow nil}
          html (sidebar/render-sidebar-html detail)]
      ;; All sections present
      (is (re-find #"MyModule" html))
      (is (re-find #"A test module" html))
      (is (re-find #"always works" html))
      (is (re-find #"Defined Types" html))
      (is (re-find #"Operations" html))
      ;; Verify order: description before guarantees before defined types before operations
      (let [desc-pos (.indexOf html "A test module")
            guar-pos (.indexOf html "always works")
            types-pos (.indexOf html "Defined Types")
            ops-pos (.indexOf html "Operations")]
        (is (< desc-pos guar-pos) "description before guarantees")
        (is (< guar-pos types-pos) "guarantees before defined types")
        (is (< types-pos ops-pos) "defined types before interface")))))

;; ---------------------------------------------------------------------------
;; Entity rendering: section order with :schema kind (defined-in visible)

(deftest render-schema-section-order
  (testing "schema entity renders defined-in before description"
    (let [detail {:label "MyType"
                  :kind :schema
                  :parent {:id "ns:a" :label "ns.a"}
                  :description "A type definition"
                  :interface {:type :schema-def
                              :items [{:tag :map :entries []}]
                              :registry {}}
                  :schema-ids {}
                  :dataflow nil}
          html (sidebar/render-sidebar-html detail)]
      ;; Both sections present
      (is (re-find #"Defined in" html))
      (is (re-find #"A type definition" html))
      ;; Verify order: defined-in before description
      (let [defined-in-pos (.indexOf html "Defined in")
            desc-pos (.indexOf html "A type definition")]
        (is (< defined-in-pos desc-pos) "defined-in before description")))))

;; ---------------------------------------------------------------------------
;; Interface rendering: fn-inline type

(deftest render-fn-inline-interface
  (testing "fn-inline interface renders Inputs and Outputs headings with counts and items"
    (let [detail {:label "MyLeaf"
                  :kind :module
                  :description nil
                  :interface {:type :fn-inline :items []}
                  :schema-ids {}
                  :dataflow {:inputs [{:label "Foo" :id "ns:a/Foo"}
                                      {:label "Plain"}]
                             :outputs [{:label "Bar" :id "ns:b/Bar"}]}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Inputs" html) "should render Inputs heading")
      (is (re-find #"Outputs" html) "should render Outputs heading")
      ;; Counts
      (is (re-find #"\(2\)" html) "Inputs count should be 2")
      (is (re-find #"\(1\)" html) "Outputs count should be 1")
      ;; Clickable items (those with :id) use view-url
      (is (re-find #"Foo" html) "should contain input label Foo")
      (is (re-find #"Bar" html) "should contain output label Bar")
      (is (re-find #"Plain" html) "should contain plain input label")
      ;; Items with :id are clickable (have data-on:click)
      (is (re-find #"data-on:click" html)
          "items with :id should be clickable"))))

(deftest render-fn-inline-empty-io
  (testing "fn-inline with empty inputs/outputs shows None"
    (let [detail {:label "EmptyLeaf"
                  :kind :module
                  :description nil
                  :interface {:type :fn-inline :items []}
                  :schema-ids {}
                  :dataflow {:inputs [] :outputs []}}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Inputs" html))
      (is (re-find #"Outputs" html))
      (is (re-find #"None" html) "empty IO should show None"))))

;; ---------------------------------------------------------------------------
;; Interface rendering: name-list type

(deftest render-name-list-interface
  (testing "name-list interface renders Functions heading and function names"
    (let [detail {:label "MyInterface"
                  :kind :interface
                  :description "An interface"
                  :interface {:type :name-list
                              :items ["fn-a" "fn-b" "fn-c"]}
                  :schema-ids {}
                  :dataflow nil}
          html (sidebar/render-sidebar-html detail)]
      (is (re-find #"Functions" html)
          "name-list should render a Functions heading")
      (is (re-find #"fn-a" html) "should contain first function name")
      (is (re-find #"fn-b" html) "should contain second function name")
      (is (re-find #"fn-c" html) "should contain third function name"))))
