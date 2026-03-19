(ns fukan.web.views.breadcrumb-test
  "Tests for breadcrumb rendering.
   Verifies ShortLabels, CurrentItem, and ClickableItems rules from spec."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.web.views.breadcrumb :as breadcrumb]))

;; ---------------------------------------------------------------------------
;; ShortLabels: last dotted segment only

(deftest short-labels-rendering
  (testing "breadcrumb uses the label as provided (projection supplies short labels)"
    (let [items [{:id nil :label "root"}
                 {:id "com.example" :label "example"}
                 {:id "com.example.core" :label "core"}]
          html (breadcrumb/render-breadcrumb items)]
      (is (re-find #"root" html))
      (is (re-find #"example" html))
      (is (re-find #"core" html)))))

;; ---------------------------------------------------------------------------
;; CurrentItem: last item is non-clickable, styled as current

(deftest current-item-not-clickable
  (testing "last breadcrumb item has 'current' class and no data-on:click"
    (let [items [{:id nil :label "root"}
                 {:id "ns:a" :label "a"}]
          html (breadcrumb/render-breadcrumb items)]
      ;; Last item should have .current class
      (is (re-find #"crumb current" html))
      ;; The current item "a" should not be wrapped in a clickable handler
      ;; Find the current span - it should just be a span.crumb.current without data-on:click
      (is (re-find #"<span class=\"crumb current\">a</span>" html)))))

;; ---------------------------------------------------------------------------
;; ClickableItems: non-last items dispatch navigation events

(deftest clickable-ancestor-items
  (testing "non-last breadcrumb items have navigation click handlers"
    (let [items [{:id nil :label "root"}
                 {:id "ns:a" :label "a"}
                 {:id "ns:a.b" :label "b"}]
          html (breadcrumb/render-breadcrumb items)]
      ;; Root and "a" should have data-on:click with navigation URLs
      (is (re-find #"data-on:click.*sse/view" html))
      ;; Should preserve view state params
      (is (re-find #"getExpandedParam" html))
      (is (re-find #"getShowPrivateParam" html))
      (is (re-find #"getVisibleEdgeTypesParam" html)))))

;; ---------------------------------------------------------------------------
;; Separator rendering

(deftest separator-between-items
  (testing "breadcrumb items are separated by separator elements"
    (let [items [{:id nil :label "root"}
                 {:id "ns:a" :label "a"}]
          html (breadcrumb/render-breadcrumb items)]
      (is (re-find #"separator" html)))))
