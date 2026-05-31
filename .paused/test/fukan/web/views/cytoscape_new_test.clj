(ns fukan.web.views.cytoscape-new-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.web.views.cytoscape :as cyto]))

(deftest transform-primitive-node
  (let [graph {:nodes [{:id "m::R" :kind :primitive/rule :label "R"}]
               :edges []}
        out (cyto/graph->cytoscape graph nil [])]
    (is (= "primitive/rule" (-> out :nodes first :kind))
        "kernel kind keyword renders verbatim")))

(deftest transform-projects-edge-with-drift
  (let [graph {:nodes []
               :edges [{:id "e0" :from "m::R" :to "artifact:code/function:clojure:m/r"
                        :kind :relation/projects
                        :projection-kind :projection-kind/rule
                        :validity :absent}]}
        out (cyto/graph->cytoscape graph nil [])
        edge (first (:edges out))]
    (is (= "relation/projects" (:edgeType edge)))
    (is (= "projection-kind/rule" (:projectionKind edge)))
    (is (= "absent" (:drift edge)))))

(deftest transform-projects-edge-valid-no-drift
  (let [graph {:nodes []
               :edges [{:id "e0" :from "a" :to "b"
                        :kind :relation/projects :validity :valid}]}
        out (cyto/graph->cytoscape graph nil [])
        edge (first (:edges out))]
    (is (nil? (:drift edge)) "valid edges carry no drift class")))
