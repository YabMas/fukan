(ns fukan.canvas.vocab.event
  "Methodology vocabulary for event-driven systems.
   Opt-in: require this namespace only if your project models named events
   and reactive handlers explicitly.

   ## Motivation

   Emerged from Phase 4 Sprint 3 stress-test against an event-driven
   e-commerce paradigm (5 modules: cart, order, payment, shipping,
   notification). The rule-of-three threshold was reached by:
     - `event` lift:   10 instances across 4 modules (cart×3, order×1,
                        payment×3, shipping×3)
     - `handler` lift: 6 instances across 4 modules (order×1, payment×1,
                        shipping×1, notification×3)

   ## Lifts

   `event` — declares a named event with a payload shape. Semantically
   distinct from `record` (events are messages, not data structures).
   Produces a no-shape Affordance with role `:canvas/event` plus a
   sibling :event/payload attribute holding the payload shape.

   `handler` — declares a function that reacts to an event. Produces an
   Affordance with role `:canvas/handler`. The `on` form names the event
   being handled (a keyword reference); the `emits` form (repeatable)
   declares which events this handler can fire next.

   ## What was NOT shipped (rule-of-three evidence)

   `topic` — 0 instances observed. The stress-test modules used direct
   handler coupling, not named channels. Deferred.

   `emits` as standalone lift — the emits semantic recurs but is naturally
   a form within `handler` (and within `function` via the existing `effect`
   form). No case for a standalone lift."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.shape :as shape]
            [datascript.core :as d]))

(defn- emit-payload-refs!
  "Walk a parsed payload shape and emit :references Relations for any :ref nodes."
  [from-id parsed-shape]
  (when parsed-shape
    (case (:kind parsed-shape)
      :ref      (h/declare-relation from-id :references (:target parsed-shape))
      :optional (emit-payload-refs! from-id (:inner parsed-shape))
      :list     (emit-payload-refs! from-id (:elem parsed-shape))
      :set      (emit-payload-refs! from-id (:elem parsed-shape))
      :sum      (run! #(emit-payload-refs! from-id %) (:variants parsed-shape))
      :tuple    (run! #(emit-payload-refs! from-id %) (:elems parsed-shape))
      :record   (run! (fn [[_ s]] (emit-payload-refs! from-id s)) (:fields parsed-shape))
      nil)))

(defconstructor event
  "A named event declaration with an optional payload shape.
   Events are messages, not data structures — semantically distinct from
   `record` even when they carry fields.

   Example:
     (event \"ItemAdded\"
       \"An item was added to the cart.\"
       (payload [item_id :String] [qty :Integer]))

   The `payload` form takes field pairs like `takes` in `function`."

  (form payload "Payload fields: zero or more [name :Type] pairs." :shape :field+)

  (produces [name doc forms]
    (let [payload-vecs  (:payload forms)
          payload-args  (when payload-vecs (apply concat payload-vecs))
          field-pairs   (if payload-args
                          (vec (->> (partition 2 payload-args)
                                    (mapv (fn [[n s]] [n (shape/parse s)]))))
                          [])
          payload-shape {:kind :record :fields field-pairs}
          aff (h/declare-affordance name
                :role :canvas/event
                :shape payload-shape
                :doc doc)]
      (emit-payload-refs! (:id aff) payload-shape)
      aff)))

(defconstructor handler
  "A reactive handler that fires when a named event arrives.
   Semantically distinct from `function`: handlers are invoked by the
   event bus, not by direct call.

   Example:
     (handler \"handle_cart_checked_out\"
       \"Place an order when a cart is checked out.\"
       (on :cart/CartCheckedOut)
       (emits :order/OrderPlaced))

   The `on` form names the incoming event (a namespaced keyword reference).
   The `emits` form (repeatable) names events this handler can produce."

  (form on     "The event this handler reacts to." :shape :name-ref :required true)
  (form emits  "An event this handler may emit."   :shape :name-ref :repeatable true)

  (produces [name doc forms]
    (let [on-kw    (first (:on forms))
          aff      (h/declare-affordance name
                     :role :canvas/handler
                     :formal-expression {:on (str on-kw)
                                         :emits (mapv #(str (first %)) (:emits forms))}
                     :doc doc)]
      ;; Emit :references for the incoming event
      (when (keyword? on-kw)
        (h/declare-relation (:id aff) :references on-kw))
      ;; Emit :references for each emitted event
      (doseq [emit-args (:emits forms)]
        (let [emit-kw (first emit-args)]
          (when (keyword? emit-kw)
            (h/declare-relation (:id aff) :references emit-kw))))
      aff)))
