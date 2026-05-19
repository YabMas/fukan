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
      ;; The fukan corpus has 6 .allium files (infra, web, web/views,
      ;; web/views/projection, model, model/pipeline) — substantially more
      ;; than the Plan-1 fixture's 2 primitives. A floor of 20 catches a
      ;; regression to the fixture while staying robust to incidental
      ;; spec edits.
      (is (>= (count (:primitives model)) 20)
          "loaded Model contains real Allium content, not the Plan-1 fixture")
      ;; Each .allium file gets a module-Container with an Allium::Module tag.
      (let [module-tags (filter #(= {:namespace "Allium" :name "Module"} (:tag %))
                                (:tag-apps model))]
        (is (= 6 (count module-tags))
            "Allium::Module tag applied to each of the 6 corpus files")))))
