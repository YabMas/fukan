(ns fukan.canvas.project.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.registry :as registry]))

(deftest all-projections-returns-vector
  (testing "all-projections returns a sorted vector of [lens dispatch-key] pairs"
    (let [v (registry/all-projections)]
      (is (vector? v))
      (is (not-any? #(= :default %) v)))))

(deftest projection-for-resolves-via-dispatch-key
  (testing "registering a temp defmethod is discoverable through projection-for"
    (let [el {:model-element-kind :Module
              :stable-id          "test.module"}]
      (try
        (defmethod core/project [:test :Module]
          [_ _ _]
          {:projection-kind    :test/module-to-something
           :lens-id            :test
           :model-element-kind :Module
           :model-element-id   "test.module"
           :target             {:path "x.clj" :namespace "x" :symbol "x"}
           :template           nil
           :prose              "x"
           :context            {}})
        (is (some? (registry/projection-for :test el)))
        (is (nil? (registry/projection-for :no-such-lens el)))
        (finally
          (remove-method core/project [:test :Module]))))))
