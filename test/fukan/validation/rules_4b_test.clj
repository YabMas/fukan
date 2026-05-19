(ns fukan.validation.rules-4b-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.validation.rules-4b :as r4b]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.vocabulary :as v]))

(deftest event-with-no-declaration-sites-is-error
  (let [model      (-> (build/empty-model)
                       (build/add-primitive
                         (p/make-event {:id    "m::events::Orphan"
                                        :label "Orphan"})))
        violations (r4b/check model)
        relevant   (filter #(= :4b/event-no-declaration-site (:kind %))
                           violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))
    (is (= "m::events::Orphan" (-> relevant first :location :event-id)))))

(deftest event-shape-mismatch-stored-becomes-violation
  (let [model      (assoc-in (build/empty-model)
                             [:phase4-state :event-shape-mismatches]
                             [{:event-id     "m::events::Bad"
                               :module-coord "m"
                               :reason       :arity-mismatch
                               :shapes       [{:kind :provides
                                               :shape {:arity 1}}
                                              {:kind :emits
                                               :shape {:arity 2}}]}])
        violations (r4b/check model)
        relevant   (filter #(= :4b/event-shape-mismatch (:kind %))
                           violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))
    (is (= "m::events::Bad" (-> relevant first :location :event-id)))
    (is (= "m"              (-> relevant first :location :module)))))

(deftest clean-events-produce-no-4b-errors
  (let [model  (-> (build/empty-model)
                   (build/add-primitive
                     (p/make-event {:id    "m::events::Good"
                                    :label "Good"}))
                   (build/add-tag-application
                     (v/make-tag-application
                       {:tag     {:namespace "Allium" :name "Event"}
                        :target  {:case :target/primitive
                                  :id   "m::events::Good"}
                        :payload {:declaration-sites ["provides"]}})))
        errors (filter #(= :error (:severity %)) (r4b/check model))]
    (is (empty? errors))))

(deftest provides-without-external-stimulus-trigger-is-error
  (testing "provides: Event must have an external-stimulus triggers consumer in the same module"
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Surface"}
                         :target {:case :target/primitive :id "m::S"}}))
                    (build/add-primitive (p/make-event {:id "m::events::E" :label "E"}))
                    (build/add-edge
                      (r/make-edge :relation/provides
                                   (r/primitive-ref "m::S")
                                   (r/primitive-ref "m::events::E"))))
          violations (r4b/check model)
          relevant (filter #(= :4b/provides-no-external-stimulus (:kind %)) violations)]
      (is (= 1 (count relevant)))
      (is (= :error (-> relevant first :severity))))))

(deftest provides-with-external-stimulus-trigger-passes
  (let [trigger-edge (r/make-edge :relation/triggers
                                  (r/primitive-ref "m::events::E")
                                  (r/primitive-ref "m::R"))
        model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive (p/make-event {:id "m::events::E" :label "E"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-edge (r/make-edge :relation/provides
                                               (r/primitive-ref "m::S")
                                               (r/primitive-ref "m::events::E")))
                  (build/add-edge trigger-edge)
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Trigger"}
                       :target {:case :target/edge
                                :edge-identity (r/edge-identity trigger-edge)}
                       :payload {:kind "external_stimulus"}})))
        violations (r4b/check model)
        relevant (filter #(= :4b/provides-no-external-stimulus (:kind %)) violations)]
    (is (empty? relevant))))
