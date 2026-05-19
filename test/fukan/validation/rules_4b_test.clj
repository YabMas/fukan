(ns fukan.validation.rules-4b-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4b :as r4b]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
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
