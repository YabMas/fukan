# event-driven demo — canvas upper-bound stress-test

Five modules of an e-commerce order-fulfillment flow, using event-driven
architecture. Tests whether the canvas substrate accommodates Event/Handler
vocabulary and reactive semantics.

## Modules

| Namespace | Content |
|-----------|---------|
| `demo.event-driven.cart` | Cart record + CartCreated/ItemAdded/CartCheckedOut events |
| `demo.event-driven.order` | Order record + OrderPlaced event + handler for CartCheckedOut |
| `demo.event-driven.payment` | Payment record + PaymentRequested/PaymentSucceeded/PaymentFailed events + handler |
| `demo.event-driven.shipping` | Shipping record + ShippingRequested/ShipmentDispatched/ShipmentDelivered events + handler |
| `demo.event-driven.notification` | Handlers for payment + shipment events (3 handler instances) |

## What vocab was needed

- `construction/record` — aggregate state shapes
- `vocab.event/event` — named event declarations with payload (10 instances, rule-of-three met)
- `vocab.event/handler` — reactive handler declarations (6 instances, rule-of-three met)
- `construction/function` — non-reactive operations (queries, mutations)
- `construction/exports` — module closure

`vocab.event` was shipped specifically for this stress-test.

## Rule-of-three evidence

| Lift | Instances | Modules | Verdict |
|------|-----------|---------|---------|
| `event` | 10 | cart(3), order(1), payment(3), shipping(3) | SHIP |
| `handler` | 6 | order(1), payment(1), shipping(1), notification(3) | SHIP |
| `topic` | 0 | — | DEFER |
| `emits` (standalone) | 0 | used as form within `handler` | DEFER |

## Findings

See `doc/plans/2026-05-26-stress-test-findings.md`.
