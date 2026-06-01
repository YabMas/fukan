(ns fukan.target.clojure.analyzer-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest duplicate-canonical-address-emits-error
  ;; Fixture trick: two files at the same ns with the same defn. Real Clojure
  ;; would refuse to load both, but the source walker just READS forms as data,
  ;; so it surfaces the duplicate.
  (let [model (-> (analyzer/run (build/empty-model)
                                (registry/make-registry)
                                "test/fixtures/clojure-projects/dup"))
        dup-violations (filter #(= :phase6/duplicate-canonical-address (:kind %))
                               (:violations model))]
    (is (>= (count dup-violations) 1)
        "at least one duplicate-canonical-address violation emitted")
    (is (= :error (-> dup-violations first :severity)))))
