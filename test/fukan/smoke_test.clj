(ns fukan.smoke-test
  "End-to-end smoke tests for the build pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.infra.model :as infra-model]
            [fukan.model.build :as b]
            [malli.core :as m]))

(deftest pipeline-loads-clean-model
  (testing "infra-model/load-model returns a validated Model (canvas is sole spec source)"
    (let [model (infra-model/load-model "src")]
      (is (m/validate b/Model model))
      (is (contains? model :violations)
          "model carries :violations key from phase 4/5"))))
