(ns demo.event-driven.notification-test
  "Smoke tests for the notification canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.event-driven.notification :as notification]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (notification/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the event-driven.notification module"
    (let [db (notification/build-canvas)
          modules (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/module)]
      (is (= ["event-driven.notification"] modules)))))

(deftest build-canvas-has-three-handlers
  (testing "db contains all 3 notification handlers (rule-of-three confirmation)"
    (let [db (notification/build-canvas)
          handlers (set (map first (d/q '[:find ?n :in $ % :where (direct-kind ?e :canvas/handler) [?e :entity/name ?n]] db classification/rules)))]
      (is (= #{"handle_payment_succeeded"
               "handle_payment_failed"
               "handle_shipment_dispatched"}
             handlers)))))

(deftest build-canvas-has-no-events
  (testing "notification module declares no events of its own"
    (let [db (notification/build-canvas)
          events (d/q '[:find ?n :in $ % :where (direct-kind ?e :canvas/event) [?e :entity/name ?n]] db classification/rules)]
      (is (empty? events)))))

(deftest handlers-reference-upstream-events
  (testing "all handlers emit :references to their upstream events"
    (let [db (notification/build-canvas)
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :event-driven.payment/PaymentSucceeded))
      (is (contains? refs :event-driven.payment/PaymentFailed))
      (is (contains? refs :event-driven.shipping/ShipmentDispatched)))))
