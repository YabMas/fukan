(ns demo.event-driven.cart-test
  "Smoke tests for the cart canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.event-driven.cart :as cart]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (cart/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the event-driven.cart module"
    (let [db (cart/build-canvas)
          modules (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/module)]
      (is (= ["event-driven.cart"] modules)))))

(deftest build-canvas-has-events
  (testing "db contains all 3 cart events with :canvas/event role"
    (let [db (cart/build-canvas)
          events (set (map first (d/q '[:find ?n :in $ % :where (direct-kind ?e :canvas/event) [?e :entity/name ?n]] db classification/rules)))]
      (is (= #{"CartCreated" "ItemAdded" "CartCheckedOut"} events)))))

(deftest build-canvas-has-records
  (testing "db contains Cart and LineItem types"
    (let [db (cart/build-canvas)
          types (set (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/type))]
      (is (contains? types "Cart"))
      (is (contains? types "LineItem")))))
