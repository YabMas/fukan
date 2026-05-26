(ns canvas.web.views.graph-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.web.views.graph :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "web.views.graph") "module present")
    (is (contains? names "ViewState") "ViewState record present")
    (is (contains? names "NavigationState") "NavigationState record present")
    (is (contains? names "DataInEventsOut") "DataInEventsOut invariant present")
    (is (contains? names "ViewStateOwnership") "ViewStateOwnership invariant present")
    (is (contains? names "RenderModeDetection") "RenderModeDetection invariant present")
    (is (contains? names "RenderingPurity") "RenderingPurity invariant present")
    (is (contains? names "AtomicUpdate") "AtomicUpdate invariant present")
    (is (contains? names "GraphSelectionDefault") "GraphSelectionDefault invariant present")
    (is (contains? names "render_graph") "render_graph function present")))
