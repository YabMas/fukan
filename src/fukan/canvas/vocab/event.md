# Event Vocabulary

Lifts in `fukan.canvas.vocab.event`. Opt-in: require this namespace only if your
project models named events and reactive handlers explicitly.

Emerged from Phase 4 Sprint 3 stress-test against an event-driven e-commerce
paradigm (5 modules: cart, order, payment, shipping, notification).

Ships two lifts: `event` and `handler`.

---

## `event`

A named event declaration with an optional payload shape. Events are messages —
semantically distinct from `record` even when they carry fields.

```clojure
(event "CartCheckedOut"
  "The cart was submitted for checkout. No further items can be added."
  (payload [cart_id  :String]
           [user_id  :String]
           [items    (list-of :LineItem)]))
```

```clojure
(event "PaymentSucceeded"
  "The payment was successfully processed."
  (payload [payment_id :String]
           [order_id   :String]))
```

Events with no payload (marker events):

```clojure
(event "SystemReady"
  "The system has completed initialization.")
```

Produces an Affordance with:
- role `:canvas/event`
- shape `{:kind :record :fields [...]}` (the payload; empty record for no-payload events)
- `:references` Relations for any ref types in the payload (auto-emitted)

### Forms

- `(payload [name :Type] ...)` — payload fields. Optional; omit for zero-payload events.

### When not to use `event`

- For a reusable data type that gets passed around → use `record`.
- For a callable entry point → use `function`.

---

## `handler`

A reactive handler that fires when a named event arrives. Semantically distinct
from `function`: handlers are invoked by the event bus, not by direct call.

```clojure
(handler "handle_cart_checked_out"
  "Create an order when a cart is checked out."
  (on :event-driven.cart/CartCheckedOut)
  (emits :event-driven.order/OrderPlaced))
```

```clojure
(handler "handle_order_placed"
  "Initiate a payment when an order is placed."
  (on :event-driven.order/OrderPlaced)
  (emits :event-driven.payment/PaymentRequested))
```

Handlers that emit multiple downstream events:

```clojure
(handler "handle_payment_succeeded"
  "Trigger shipping and notify the customer when payment clears."
  (on :event-driven.payment/PaymentSucceeded)
  (emits :event-driven.shipping/ShipmentRequested)
  (emits :event-driven.notification/PaymentConfirmed))
```

Produces an Affordance with:
- role `:canvas/handler`
- `:affordance/formal-expression` `{:on "<kw>" :emits ["<kw>" ...]}`
- `:references` Relations to the `on` event and all `emits` events (auto-emitted)

### Forms

- `(on :module/EventName)` — the incoming event. Required. Namespaced keyword.
- `(emits :module/EventName)` — an event this handler may produce. Repeatable. Optional.

### Cross-module event references

Event references always use namespaced keywords matching the owning module's name:

```clojure
;; cart module name is "event-driven.cart"
;; its event "CartCheckedOut" is referenced as:
(on :event-driven.cart/CartCheckedOut)
```

### When to use `function` instead

- The callable is invoked synchronously by direct call, not by event bus → use `function`.
- The reaction has typed inputs beyond the event payload → use `function` with `(takes ...)`.
