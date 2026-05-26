# Phase 4 Sprint 3 — Stress-test Findings

**Date:** 2026-05-26
**Status:** Complete

## Tests

Two paradigm stress-tests against the canvas substrate's
architecture-neutrality claim:

- **Test I (event-driven):** upper-bound — 5 modules of an order-fulfillment
  service with Event/Handler/Topic vocabulary
- **Test IV (static-lib):** lower-bound — 5 modules of types + pure
  functions, no behavioral content

Both demos live in `/demo/`. Not on the default classpath.
Load interactively: `clj -M:dev:demo` then `(user/load-demo "…")`.
Run tests: `clj -M:demo`.

---

## Test IV — Static library (lower bound)

### What was ported

Five modules of a minimal 2D/3D geometry library:

| Module | Content |
|--------|---------|
| `static-lib.vec2` | `Vec2` record + add/sub/dot/magnitude/normalize |
| `static-lib.vec3` | `Vec3` record + add/sub/dot/cross/magnitude/normalize |
| `static-lib.matrix` | `Matrix3x3` record + identity_matrix/add/mul/transpose/determinant/scale_uniform |
| `static-lib.transform` | `Transform` record + identity_transform/translation/rotation/compose/invert |
| `static-lib.operations` | Cross-cutting: apply_transform_to_vec3/project_vec3_to_vec2/embed_vec2_in_vec3/lerp_vec2/lerp_vec3/apply_transform_to_vec2 |

### Lifts used

Exclusively `construction` primitives: `record`, `function`, `exports`.
No behavioral lifts required. No new vocabulary was needed or considered.

### Did the substrate hold without primitive changes?

Yes. Zero friction. Every concept (type, function, closure) mapped cleanly
onto existing construction primitives. The `operations` module uses namespaced
keyword cross-module references (`:static-lib.vec3/Vec3`) which the substrate
handled via its existing segment-matching resolution.

### Anything that couldn't be modeled?

Nothing. The substrate's `record`+`function`+`exports` triplet is exactly
right for a static library. `optional` worked for nullable returns (normalize).
The `list-of` combinator handled list-typed parameters.

### How does the demo read aloud?

At module-design altitude. A reader of `vec3.clj` sees: "Vec3 is a 3D vector.
It supports add, sub, dot, cross, magnitude, normalize." No substrate plumbing
visible. The canvas port is indistinguishable from a design spec.

### Verdict

**The substrate is NOT over-built for trivial cases.** Porting a static lib
felt lighter than expected. The `construction` namespace does its job cleanly
with no ceremony leaking through. A project that only uses `construction`
never needs to know `vocab.behavioral` or `vocab.event` exist.

---

## Test I — Event-driven service (upper bound)

### What was ported

Five modules of an order-fulfillment flow:

| Module | Content |
|--------|---------|
| `event-driven.cart` | `Cart`/`LineItem` records + CartCreated/ItemAdded/CartCheckedOut events + 4 command functions |
| `event-driven.order` | `Order` record + OrderPlaced event + handle_cart_checked_out handler + 2 functions |
| `event-driven.payment` | `Payment` record + PaymentRequested/PaymentSucceeded/PaymentFailed events + handle_order_placed handler + 2 functions |
| `event-driven.shipping` | `Shipment` record + ShippingRequested/ShipmentDispatched/ShipmentDelivered events + handle_payment_succeeded handler + 3 functions |
| `event-driven.notification` | 3 handlers for payment/shipping events + send_notification function |

### Lifts needed beyond existing vocab

Two new lifts shipped in `src/fukan/canvas/vocab/event.clj`:

**`event`** — declares a named event with a payload shape. Distinct from
`record` because events are messages (semantically), not owned data
structures. Produces a `:canvas/event` Affordance with the payload stored
as its shape. Rule-of-three evidence: 10 instances across 4 modules.

**`handler`** — declares a reactive function that fires when a named event
arrives. Distinct from `function` because handlers have reactive semantics
(invoked by an event bus, not by direct call). Has `on` (required, the
incoming event) and `emits` (repeatable, downstream events). Rule-of-three
evidence: 6 instances across 4 modules.

### Rule-of-three table

| Lift | Instances | Modules | Verdict |
|------|-----------|---------|---------|
| `event` | 10 | cart(3), order(1), payment(3), shipping(3) | SHIPPED |
| `handler` | 6 | order(1), payment(1), shipping(1), notification(3) | SHIPPED |
| `topic` | 0 | — | DEFERRED (not observed) |
| `emits` (standalone) | — | form within `handler` | DEFERRED (covered by `handler`) |

### Did the substrate hold without primitive changes?

Yes. The six substrate primitives (Module, Affordance, State, Type, Relation,
Tag) were sufficient. `vocab.event` added two new roles (`:canvas/event`,
`:canvas/handler`) and a new semantic convention (`formal-expression` stores
`{:on "…" :emits ["…" …]}`), but nothing in the substrate had to change.

### Anything that couldn't be modeled?

One semantic gap observed but not blocking:

**`emits` in `function`** — command functions like `checkout` in the cart
module say "emits CartCheckedOut" in their docstring, but the canvas port
uses a docstring annotation rather than a first-class `emits` form. The
existing `effect` form in `construction/function` covers imperative effects;
there's no structural way to say "this function emits an event that downstream
handlers will react to" without a new form. This is a design question for Phase
5, not a blocking gap. Using the docstring is a reasonable encoding for now.

**Event sourcing** — if a project wanted to model the event log itself
(aggregate reconstruction from event stream), the substrate has no `stream`
or `projection` primitive. This was deliberately out of scope for the demo,
but it would be the next threshold if event-sourcing paradigms are stress-tested.

### How does the demo read aloud?

At module-design altitude. A reader of `payment.clj` sees: "Payment is a
record. The module handles OrderPlaced by requesting payment; it can emit
PaymentRequested/PaymentSucceeded/PaymentFailed." The reactive structure
is self-evident from the lifts without knowing the substrate. The `(on …)`
and `(emits …)` forms read as direct domain vocabulary.

---

## Cross-test synthesis

### Lower bound: is the substrate over-built?

No. The `construction` namespace is the right minimal core. Adding behavioral
content via opt-in vocab libraries feels like using a good tool rather than
fighting a heavy framework. The static-lib port verified that an author who
only needs types and functions never pays the cost of behavioral machinery.

### Upper bound: does architecture-neutrality hold under stress?

Yes, with one caveat. The event-driven paradigm required two new vocabulary
lifts (`event` and `handler`), but both were additive — they extended the
vocabulary layer, not the substrate primitives. The substrate's separation of
concerns held: Module/Affordance/State/Type/Relation/Tag remained unchanged.
The only integration point needed was assigning new roles (`:canvas/event`,
`:canvas/handler`) to Affordance entities and storing semantic conventions in
`formal-expression`.

The caveat: **cross-module event references use namespaced keywords
(`:event-driven.payment/PaymentSucceeded`)**, and the current segment-matching
resolution in `canvas-source` uses the namespace hint to find the right module.
This works for the demo because all modules have distinct path segments. In a
real project where two modules share a segment name, references would be
ambiguous. This is the same Sprint 1 deferred item; the stress-test confirms it
would surface in event-driven architectures too (cross-module event references
are more common there than in monolith-function designs).

### What each test found that the other didn't

**Static-lib found:** the substrate works cleanly when there's NO behavioral
content at all. There's no overhead to opting out of behavioral vocab. The
`construction` + `exports` primitive is the right minimum.

**Event-driven found:** the vocabulary layer IS the right abstraction boundary.
When a new paradigm adds concepts, they belong in vocabulary (new lifts, new
roles, new formal-expression conventions) not in substrate primitives. The
substrate's architecture-neutrality claim is well-founded.

## Surprises

**Most surprising finding:** The notification module — a module with only
handlers and no events of its own — was the clearest validation of the handler
lift. A notification boundary in an event-driven system IS just a collection of
named reactions. Having `(handler "handle_payment_succeeded" … (on :payment/PaymentSucceeded))`
read as a complete module description (no surrounding ceremony) confirmed that
`handler` is the right altitude for the concept. The lift says "here is a named
reaction to a named event" without any framework plumbing visible.

**Second surprise (infrastructure):** Demo test classpath layout. The `:demo`
alias uses `"demo"` and `"test"` as extra-paths (not a separate `test/demo`
root), so test namespaces carry the `demo.` prefix (e.g.
`demo.event-driven.cart-test`). This means `:demo` tests and `:test` tests
share the `test/` classpath root without conflict — the demo tests are just
files in a subdirectory of `test/`, invisible to `:test`'s runner because their
fully-qualified names (starting with `demo.`) don't collide with fukan's own
test namespaces. An accidental discovery: the single-root approach is cleaner
than a separate `test/demo` source root.

## Test counts

| Suite | Tests | Assertions |
|-------|-------|------------|
| Full project (`:test`) | 667 | 1736 |
| Demo (`:demo`) | 43 | 52 |
| Sprint 2 baseline (`:test`) | 614 | 1669 |
| Delta (vocab.event + new canvas tests) | +53 | +67 |
