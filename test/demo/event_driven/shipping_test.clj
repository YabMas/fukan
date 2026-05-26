(ns demo.event-driven.shipping-test
  "Smoke tests for the shipping canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.event-driven.shipping :as shipping]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (shipping/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the event-driven.shipping module"
    (let [db (shipping/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["event-driven.shipping"] modules)))))

(deftest build-canvas-has-shipping-events
  (testing "db contains all 3 shipping events"
    (let [db (shipping/build-canvas)
          events (set (map first (d/q '[:find ?n
                                        :where [?e :affordance/role :canvas/event]
                                               [?e :entity/name ?n]]
                                      db)))]
      (is (= #{"ShippingRequested" "ShipmentDispatched" "ShipmentDelivered"} events)))))

(deftest build-canvas-has-payment-handler
  (testing "db contains handle_payment_succeeded handler"
    (let [db (shipping/build-canvas)
          handlers (set (map first (d/q '[:find ?n
                                          :where [?e :affordance/role :canvas/handler]
                                                 [?e :entity/name ?n]]
                                        db)))]
      (is (contains? handlers "handle_payment_succeeded")))))

(deftest handler-references-payment-event
  (testing "handler references PaymentSucceeded from payment module"
    (let [db (shipping/build-canvas)
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :event-driven.payment/PaymentSucceeded)))))
