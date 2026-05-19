(ns fukan.constraint.well-known-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.well-known :as wk]
            [fukan.constraint.phase5 :as phase5]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.vocabulary :as v]))

(defn- run-with [model regs]
  (-> model
      (update :predicates (fnil into []) regs)
      phase5/run))

(defn- phase5-violations [model]
  (filter #(= :phase5 (:phase %)) (:violations model)))

(deftest signal-gap-fires-on-event-with-no-consumer
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive
                    (p/make-event {:id "m::events::E" :label "E"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Surface"}
                       :target {:case :target/primitive :id "m::S"}}))
                  (build/add-edge
                    (r/make-edge :relation/provides
                                 (r/primitive-ref "m::S")
                                 (r/primitive-ref "m::events::E"))))
        m1 (run-with model [(wk/signal-gap)])]
    (is (pos? (count (phase5-violations m1)))
        "signal_gap fires when provided Event has no triggers consumer")))

(deftest signal-gap-silent-when-event-is-consumed
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive
                    (p/make-event {:id "m::events::E" :label "E"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-edge
                    (r/make-edge :relation/provides
                                 (r/primitive-ref "m::S")
                                 (r/primitive-ref "m::events::E")))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "m::events::E")
                                 (r/primitive-ref "m::R"))))
        m1 (run-with model [(wk/signal-gap)])]
    (is (zero? (count (phase5-violations m1))))))

(deftest external-must-have-wrapper-fires-on-orphan
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "orphan::Foo" :label "Foo"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "ExternalEntity"}
                       :target {:case :target/primitive :id "orphan::Foo"}})))
        m1 (run-with model [(wk/external-must-have-wrapper)])]
    (is (pos? (count (phase5-violations m1)))
        "external entity has no known wrapping module")))

(deftest no-circular-refs-fires-on-self-edge
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "x" :label "x"}))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "x")
                                 (r/primitive-ref "x"))))
        m1 (run-with model [(wk/no-circular-refs)])]
    (is (pos? (count (phase5-violations m1)))
        "self-edge detected as circular reference")))

(deftest no-circular-refs-fires-on-transitive-cycle
  ;; a → b → a: not a self-edge but a 2-cycle; depends-on closure detects it.
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "a" :label "a"}))
                  (build/add-primitive (p/make-container {:id "b" :label "b"}))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "a")
                                 (r/primitive-ref "b")))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "b")
                                 (r/primitive-ref "a"))))
        m1 (run-with model [(wk/no-circular-refs)])]
    (is (pos? (count (phase5-violations m1)))
        "transitive 2-cycle detected as circular reference")))

(deftest no-dependency-fires-on-indirect-dependency
  ;; tagged-A → mid → tagged-B: depends-on closes over the intermediate hop.
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "a" :label "a"}))
                  (build/add-primitive (p/make-container {:id "mid" :label "mid"}))
                  (build/add-primitive (p/make-container {:id "b" :label "b"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Test" :name "LayerA"}
                       :target {:case :target/primitive :id "a"}}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Test" :name "LayerB"}
                       :target {:case :target/primitive :id "b"}}))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "a")
                                 (r/primitive-ref "mid")))
                  (build/add-edge
                    (r/make-edge :relation/triggers
                                 (r/primitive-ref "mid")
                                 (r/primitive-ref "b"))))
        m1 (run-with model [(wk/no-dependency "Test::LayerA" "Test::LayerB")])]
    (is (pos? (count (phase5-violations m1)))
        "indirect dependency detected by no-dependency via depends-on closure")))
