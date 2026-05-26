(ns demo.event-driven.order
  "Canvas port — order aggregate with events and a cart handler.
   Upper-bound stress-test: event + handler + construction vocabulary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.event :refer [event handler]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "event-driven.order"

      ;; Aggregate state
      (record "Order"
        "An order created from a checked-out cart."
        (field order_id   :String)
        (field cart_id    :String)
        (field user_id    :String)
        (field items      (list-of :event-driven.cart/LineItem))
        (field total      :Double)
        (field status     :String))

      ;; Events
      (event "OrderPlaced"
        "An order was placed after cart checkout."
        (payload [order_id  :String]
                 [cart_id   :String]
                 [user_id   :String]
                 [total     :Double]))

      ;; Reactive handler: fires when cart checks out
      (handler "handle_cart_checked_out"
        "Create an order when a cart is checked out."
        (on :event-driven.cart/CartCheckedOut)
        (emits :event-driven.order/OrderPlaced))

      ;; Query operations
      (function "get_order"
        "Retrieve an order by id."
        (takes [order_id :String])
        (gives (optional :Order)))

      (function "calculate_total"
        "Sum the line item prices for a list of cart items."
        (takes [items (list-of :event-driven.cart/LineItem)])
        (gives :Double))

      (exports Order))))
