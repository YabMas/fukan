(ns canvas.web.views.cytoscape-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.web.views.cytoscape :as port]
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
    (is (contains? names "web.views.cytoscape") "module present")
    (is (contains? names "CytoscapeGraph") "CytoscapeGraph value present")
    (is (contains? names "CytoscapeNode") "CytoscapeNode value present")
    (is (contains? names "CytoscapeEdge") "CytoscapeEdge value present")
    (is (contains? names "CytoscapeIdentifiers") "CytoscapeIdentifiers invariant present")
    (is (contains? names "CytoscapeEdgeTypeMirrorsKind") "CytoscapeEdgeTypeMirrorsKind invariant present")
    (is (contains? names "CytoscapeProjectionFieldsScope") "CytoscapeProjectionFieldsScope invariant present")))
