(ns fukan.canvas.vocab.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.event :refer [event handler]]
            [datascript.core :as d]))

;; ---------------------------------------------------------------------------
;; event lift tests
;; ---------------------------------------------------------------------------

(deftest event-creates-affordance-with-role
  (testing "(event …) produces an Affordance with :canvas/event role"
    (let [db (h/with-canvas
               (h/within-module "demo.cart"
                 (event "CartCreated"
                   "A new shopping cart was created."
                   (payload [cart_id :String]
                            [user_id :String]))))
          rows (d/q '[:find ?n ?r
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]]
                    db)]
      (is (= 1 (count rows)))
      (is (= ["CartCreated" :canvas/event] (first rows))))))

(deftest event-payload-shape-stored
  (testing "(event …) stores a :record payload shape"
    (let [db (h/with-canvas
               (h/within-module "demo.cart"
                 (event "ItemAdded"
                   "An item was added to the cart."
                   (payload [item_id :String] [qty :Integer]))))
          rows (d/q '[:find ?sh
                      :where [?a :entity/name "ItemAdded"]
                             [?a :affordance/shape ?sh]]
                    db)
          shape (edn/read-string (ffirst rows))]
      (is (= :record (:kind shape)))
      (is (= 2 (count (:fields shape)))))))

(deftest event-without-payload
  (testing "(event …) is valid with no payload — empty record shape"
    (let [db (h/with-canvas
               (h/within-module "demo.cart"
                 (event "CartCheckedOut"
                   "The cart was submitted for checkout.")))
          rows (d/q '[:find ?n
                      :where [?a :affordance/role :canvas/event]
                             [?a :entity/name ?n]]
                    db)]
      (is (= ["CartCheckedOut"] (mapv first rows))))))

(deftest event-emits-cross-module-refs
  (testing "(event …) emits :references Relations for typed payload fields"
    (let [db (h/with-canvas
               (h/within-module "demo.order"
                 (event "OrderPlaced"
                   "An order was placed."
                   (payload [order_id :String]
                            [cart     :cart/CartCheckedOut]))))
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :cart/CartCheckedOut)))))

(deftest event-persists-doc
  (testing "(event …) stores the docstring in :affordance/doc"
    (let [db (h/with-canvas
               (h/within-module "demo.payment"
                 (event "PaymentSucceeded"
                   "The payment was successfully processed.")))
          rows (d/q '[:find ?doc
                      :where [?a :entity/name "PaymentSucceeded"]
                             [?a :affordance/doc ?doc]]
                    db)]
      (is (= "The payment was successfully processed." (ffirst rows))))))

;; ---------------------------------------------------------------------------
;; handler lift tests
;; ---------------------------------------------------------------------------

(deftest handler-creates-affordance-with-role
  (testing "(handler …) produces an Affordance with :canvas/handler role"
    (let [db (h/with-canvas
               (h/within-module "demo.order"
                 (handler "handle_cart_checked_out"
                   "Place an order when a cart is checked out."
                   (on :cart/CartCheckedOut)
                   (emits :order/OrderPlaced))))
          rows (d/q '[:find ?n ?r
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]]
                    db)]
      (is (= 1 (count rows)))
      (is (= ["handle_cart_checked_out" :canvas/handler] (first rows))))))

(deftest handler-formal-expression-contains-on-and-emits
  (testing "(handler …) stores :on and :emits in formal-expression"
    (let [db (h/with-canvas
               (h/within-module "demo.order"
                 (handler "handle_cart_checked_out"
                   "Place an order when cart is checked out."
                   (on :cart/CartCheckedOut)
                   (emits :order/OrderPlaced))))
          rows (d/q '[:find ?fe
                      :where [?a :entity/name "handle_cart_checked_out"]
                             [?a :affordance/formal-expression ?fe]]
                    db)
          fe (edn/read-string (ffirst rows))]
      (is (= ":cart/CartCheckedOut" (:on fe)))
      (is (= [":order/OrderPlaced"] (:emits fe))))))

(deftest handler-emits-cross-module-refs
  (testing "(handler …) emits :references for both on and emits events"
    (let [db (h/with-canvas
               (h/within-module "demo.payment"
                 (handler "handle_order_placed"
                   "Request payment when an order is placed."
                   (on :order/OrderPlaced)
                   (emits :payment/PaymentRequested))))
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :order/OrderPlaced))
      (is (contains? refs :payment/PaymentRequested)))))

(deftest handler-multiple-emits
  (testing "(handler …) supports multiple emits forms"
    (let [db (h/with-canvas
               (h/within-module "demo.notification"
                 (handler "handle_payment_outcome"
                   "Notify on payment success or failure."
                   (on :payment/PaymentSucceeded)
                   (emits :notification/EmailSent)
                   (emits :notification/SmsSent))))
          rows (d/q '[:find ?fe
                      :where [?a :entity/name "handle_payment_outcome"]
                             [?a :affordance/formal-expression ?fe]]
                    db)
          fe (edn/read-string (ffirst rows))]
      (is (= 2 (count (:emits fe)))))))

(deftest handler-required-on-form
  (testing "(handler …) throws when `on` form is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (h/with-canvas
                   (h/within-module "demo.order"
                     (handler "handle_something"
                       "Missing on form.")))))))
