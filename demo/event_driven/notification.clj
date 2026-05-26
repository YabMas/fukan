(ns demo.event-driven.notification
  "Canvas port — notification module: pure handlers, no events of its own.
   Upper-bound stress-test: handler lift's clearest use case (3 handler
   instances in a single module — the notification module IS the handler boundary).
   No events emitted; no records owned."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.event :refer [handler]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "event-driven.notification"

      ;; Value type for notification results
      (record "NotificationResult"
        "Result of a notification dispatch attempt."
        (field recipient  :String)
        (field channel    :String)
        (field success    :Boolean)
        (field message    :String))

      ;; Handlers — this module's entire behavioral surface
      ;; Three handlers in one module confirms rule-of-three for handler lift.
      (handler "handle_payment_succeeded"
        "Send a payment confirmation notification when payment succeeds."
        (on :event-driven.payment/PaymentSucceeded))

      (handler "handle_payment_failed"
        "Send a payment failure notification when payment fails."
        (on :event-driven.payment/PaymentFailed))

      (handler "handle_shipment_dispatched"
        "Send a shipment tracking notification when an order ships."
        (on :event-driven.shipping/ShipmentDispatched))

      ;; Support function: non-reactive helper
      (function "send_notification"
        "Dispatch a notification to a recipient via a named channel."
        (takes [recipient :String]
               [channel   :String]
               [message   :String])
        (gives :NotificationResult)))))
