(ns event-driven.payment-test
  "Smoke tests for the payment canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.event-driven.payment :as payment]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (payment/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the event-driven.payment module"
    (let [db (payment/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["event-driven.payment"] modules)))))

(deftest build-canvas-has-payment-events
  (testing "db contains all 3 payment events"
    (let [db (payment/build-canvas)
          events (set (map first (d/q '[:find ?n
                                        :where [?e :affordance/role :canvas/event]
                                               [?e :entity/name ?n]]
                                      db)))]
      (is (= #{"PaymentRequested" "PaymentSucceeded" "PaymentFailed"} events)))))

(deftest build-canvas-has-order-handler
  (testing "db contains handle_order_placed handler"
    (let [db (payment/build-canvas)
          handlers (set (map first (d/q '[:find ?n
                                          :where [?e :affordance/role :canvas/handler]
                                                 [?e :entity/name ?n]]
                                        db)))]
      (is (contains? handlers "handle_order_placed")))))
