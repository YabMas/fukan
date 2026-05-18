(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value (per fukan.model.build/Model) and offers load /
   refresh / get. In Plan 1 the loader returns a small fixture Model;
   Plans 2/3/5 swap in real analyzers without changing this API."
  (:require [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn- fixture-model
  "A trivial Model that exercises the substrate end-to-end without an
   analyzer: one Container with one Field, one Rule, one writes edge, and
   one Allium::Entity tag application."
  []
  (let [order-c (p/make-container
                  {:id "order" :label "Order"
                   :fields [(p/make-field "total" (t/make-scalar "Integer") false)]})
        rule    (p/make-rule
                  {:id "increment-total" :label "IncrementTotal"})
        td      (v/make-tag-definition
                  {:namespace "Allium" :name "Entity"
                   :applies-to :target/container})
        ta      (v/make-tag-application
                  {:tag {:namespace "Allium" :name "Entity"}
                   :target {:case :target/primitive :id "order"}})]
    (-> (build/empty-model)
        (build/add-primitive order-c)
        (build/add-primitive rule)
        (build/add-tag-definition td)
        (build/add-tag-application ta)
        (build/add-edge
          (r/make-edge :relation/writes
                       (r/primitive-ref "increment-total")
                       (r/substrate-address "order"
                                            [{:slot "field" :key "total"}]))))))

(defn load-model
  "Build (or reload) the Model for the given src path. Plan 1 ignores `src`
   and returns a tiny fixture; later plans wire real analyzers through here."
  [src]
  (println "Loading model from" src "(Plan 1 fixture only)")
  (let [m (fixture-model)]
    (reset! state {:model m :src src})
    (println "Loaded:" (count (:primitives m)) "primitives,"
                       (count (:edges m)) "edges,"
                       (count (:tag-apps m)) "tag applications")
    m))

(defn get-model
  "Current Model, or nil if not loaded."
  []
  (:model @state))

(defn get-src
  "Current src path, or nil."
  []
  (:src @state))

(defn refresh-model
  "Rebuild from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))
