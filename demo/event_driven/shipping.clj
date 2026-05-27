(ns demo.event-driven.shipping
  "Canvas port — shipping aggregate with events and a payment handler.
   Upper-bound stress-test: event + handler + construction vocabulary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.event :refer [event handler]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "event-driven.shipping"

      ;; Aggregate state
      (record "Shipment"
        "A shipment created after payment succeeds."
        (field shipment_id  :String)
        (field order_id     :String)
        (field address      :String)
        (field status       :String))

      ;; Events emitted by the shipping flow
      (event "ShippingRequested"
        "A shipment was requested for an order."
        (payload [shipment_id  :String]
                 [order_id     :String]
                 [address      :String]))

      (event "ShipmentDispatched"
        "The shipment was handed off to a carrier."
        (payload [shipment_id    :String]
                 [tracking_code  :String]))

      (event "ShipmentDelivered"
        "The shipment was delivered to the recipient."
        (payload [shipment_id  :String]
                 [order_id     :String]))

      ;; Reactive handler: fires when payment succeeds
      (handler "handle_payment_succeeded"
        "Request shipment when payment succeeds."
        (on :event-driven.payment/PaymentSucceeded)
        (emits :event-driven.shipping/ShippingRequested))

      ;; Commands and queries
      (function "dispatch_shipment"
        "Hand a shipment off to a carrier. Emits ShipmentDispatched."
        (takes [shipment :Shipment] [tracking_code :String])
        (gives :Shipment)
        (emits ShipmentDispatched))

      (function "mark_delivered"
        "Record delivery of a shipment. Emits ShipmentDelivered."
        (takes [shipment :Shipment])
        (gives :Shipment)
        (emits ShipmentDelivered))

      (function "get_shipment"
        "Retrieve a shipment by id."
        (takes [shipment_id :String])
        (gives (optional :Shipment)))

      (exports Shipment))))
