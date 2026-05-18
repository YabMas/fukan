(ns fukan.smoke-test
  "Plan 1 end-to-end smoke test: confirms the imperative shell loads a
   Model that validates against fukan.model.build/Model and exposes the
   expected fixture primitives + edges."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.infra.model :as infra-model]
            [fukan.model.build :as b]
            [malli.core :as m]))

(deftest fixture-loader-end-to-end
  (testing "infra-model/load-model returns a Model that validates"
    (let [model (infra-model/load-model "src")]
      (is (m/validate b/Model model))
      (is (some? (b/get-primitive model "order")))
      (is (= 1 (count (b/edges-by-kind model :relation/writes)))))))
