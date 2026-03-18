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
