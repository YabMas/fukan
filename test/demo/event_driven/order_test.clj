(ns event-driven.order-test
  "Smoke tests for the order canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.event-driven.order :as order]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (order/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the event-driven.order module"
    (let [db (order/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["event-driven.order"] modules)))))

(deftest build-canvas-has-order-placed-event
  (testing "db contains OrderPlaced event"
    (let [db (order/build-canvas)
          events (set (map first (d/q '[:find ?n
                                        :where [?e :affordance/role :canvas/event]
                                               [?e :entity/name ?n]]
                                      db)))]
      (is (contains? events "OrderPlaced")))))

(deftest build-canvas-has-cart-handler
  (testing "db contains handle_cart_checked_out handler"
    (let [db (order/build-canvas)
          handlers (set (map first (d/q '[:find ?n
                                          :where [?e :affordance/role :canvas/handler]
                                                 [?e :entity/name ?n]]
                                        db)))]
      (is (contains? handlers "handle_cart_checked_out")))))

(deftest handler-references-cart-event
  (testing "handler emits :references to the CartCheckedOut event"
    (let [db (order/build-canvas)
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :event-driven.cart/CartCheckedOut)))))
