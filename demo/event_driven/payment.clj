(ns demo.event-driven.payment
  "Canvas port — payment aggregate with events and an order handler.
   Upper-bound stress-test: event + handler + construction vocabulary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.event :refer [event handler]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "event-driven.payment"

      ;; Aggregate state
      (record "Payment"
        "A payment attempt linked to an order."
        (field payment_id  :String)
        (field order_id    :String)
        (field amount      :Double)
        (field status      :String))

      ;; Events emitted by the payment flow
      (event "PaymentRequested"
        "A payment was requested for an order."
        (payload [payment_id  :String]
                 [order_id    :String]
                 [amount      :Double]))

      (event "PaymentSucceeded"
        "The payment was successfully processed."
        (payload [payment_id  :String]
                 [order_id    :String]))

      (event "PaymentFailed"
        "The payment was declined or failed."
        (payload [payment_id  :String]
                 [order_id    :String]
                 [reason      :String]))

      ;; Reactive handler: fires when an order is placed
      (handler "handle_order_placed"
        "Initiate a payment when an order is placed."
        (on :event-driven.order/OrderPlaced)
        (emits :event-driven.payment/PaymentRequested))

      ;; Commands and queries
      (function "process_payment"
        "Attempt to process a payment. Emits PaymentSucceeded or PaymentFailed."
        (takes [payment :Payment])
        (gives :Payment))

      (function "get_payment"
        "Retrieve a payment by id."
        (takes [payment_id :String])
        (gives (optional :Payment)))

      (exports Payment))))
