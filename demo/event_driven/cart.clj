(ns demo.event-driven.cart
  "Canvas port — shopping cart aggregate with events.
   Upper-bound stress-test: event + construction vocabulary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.event :refer [event]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "event-driven.cart"

      ;; Aggregate state
      (record "Cart"
        "A shopping cart with an owner and a list of line items."
        (field cart_id  :String)
        (field user_id  :String)
        (field items    (list-of :LineItem))
        (field status   :String))

      (record "LineItem"
        "A single product line in the cart."
        (field item_id  :String)
        (field qty      :Integer)
        (field price    :Double))

      ;; Events emitted by cart operations
      (event "CartCreated"
        "A new shopping cart was opened for a user."
        (payload [cart_id :String]
                 [user_id :String]))

      (event "ItemAdded"
        "A product was added to the cart."
        (payload [cart_id  :String]
                 [item_id  :String]
                 [qty      :Integer]
                 [price    :Double]))

      (event "CartCheckedOut"
        "The cart was submitted for checkout. No further items can be added."
        (payload [cart_id  :String]
                 [user_id  :String]
                 [items    (list-of :LineItem)]))

      ;; Commands (non-reactive functions)
      (function "create_cart"
        "Open a new cart for a user. Emits CartCreated."
        (takes [user_id :String])
        (gives :Cart)
        (emits CartCreated))

      (function "add_item"
        "Add a product to the cart. Emits ItemAdded."
        (takes [cart :Cart]
               [item_id :String]
               [qty :Integer]
               [price :Double])
        (gives :Cart)
        (emits ItemAdded))

      (function "checkout"
        "Submit the cart for checkout. Emits CartCheckedOut."
        (takes [cart :Cart])
        (gives :CartCheckedOut)
        (emits CartCheckedOut))

      (function "get_cart"
        "Retrieve the current cart state by id."
        (takes [cart_id :String])
        (gives (optional :Cart)))

      (exports Cart LineItem))))
