(ns fukan.web.views.shell-test
  "Smoke tests for the app shell.
   Verifies Datastar event wiring and graph-viewer element are present."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.web.views.shell :as shell]))

(deftest shell-contains-event-bindings
  (testing "app shell contains expected Datastar event bindings"
    (let [html (shell/render-app-shell)]
      (is (re-find #"data-on:graph-select" html)
          "shell should wire graph-select event")
      (is (re-find #"data-on:graph-navigate" html)
          "shell should wire graph-navigate event")
      (is (re-find #"data-on:graph-expand" html)
          "shell should wire graph-expand event"))))

(deftest shell-contains-graph-viewer
  (testing "app shell contains the graph-viewer web component"
    (let [html (shell/render-app-shell)]
      (is (re-find #"<graph-viewer" html)
          "shell should include graph-viewer element")
      (is (re-find #"data-attr:graph-data" html)
          "graph-viewer should have data-attr binding for graph data"))))
