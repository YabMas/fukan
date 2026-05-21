(ns fukan.smoke-test
  "Plan 2b end-to-end smoke test: confirms the imperative shell loads the
   real fukan corpus via the Allium pipeline and the resulting Model
   validates against fukan.model.build/Model."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.infra.model :as infra-model]
            [fukan.model.build :as b]
            [malli.core :as m]))

(deftest pipeline-loader-end-to-end
  (testing "infra-model/load-model returns a validated Model with substantive content"
    (let [model (infra-model/load-model "src")]
      (is (m/validate b/Model model))
      ;; The fukan corpus has 11 .allium files (infra/model, infra/server, web/handler,
      ;; web/views/shell, web/views/graph, web/views/sidebar, web/views/cytoscape,
      ;; web/views/breadcrumb, web/views/projection, model/spec, model/pipeline) —
      ;; substantially more than the Plan-1 fixture's 2 primitives. A floor of 20
      ;; catches a regression to the fixture while staying robust to incidental
      ;; spec edits.
      (is (>= (count (:primitives model)) 20)
          "loaded Model contains real Allium content, not the Plan-1 fixture")
      ;; Each .allium file gets a module-Container with an Allium::Module tag.
      (let [module-tags (filter #(= {:namespace "Allium" :name "Module"} (:tag %))
                                (:tag-apps model))]
        (is (= 11 (count module-tags))
            "Allium::Module tag applied to each of the 11 corpus files")))))
