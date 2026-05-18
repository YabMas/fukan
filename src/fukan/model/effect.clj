(ns fukan.model.effect
  "Effect sub-substrate (MODEL.md §3.8.2–§3.8.4).

   Effect = { kind, target, value?, source: ExprId }.
     - kind   ∈ :effect/create | :effect/write | :effect/destroy | :effect/emit
     - target ∈ PrimitiveRef | SubstrateAddress (per §4.3)
     - value  Optional Expression — absent for Destroy
     - source ExprId addressing the originating Expression in
              `Rule.intent.assertions` (per the §3.8 kernel invariant)

   Identity is (rule-id, kind, target). Plan 1 ships the data shape and
   identity. Canonicalisation (Expression → Effect, per §3.8.4 patterns) is
   deferred to Plan 2 where the Allium analyzer drives it."
  (:require [fukan.model.expression :as e]))

(def effect-kinds
  #{:effect/create :effect/write :effect/destroy :effect/emit})

(defn make-effect
  "Construct an Effect. `value` may be nil for Destroy."
  [kind target value source-expr-id]
  (assert (effect-kinds kind) (str "Unknown effect kind: " kind))
  (cond-> {:kind kind, :target target, :source source-expr-id}
    (some? value) (assoc :value value)))

(defn effect-identity
  "(rule-id, kind, target) per MODEL.md §3.8.7. Stable across semantically-
   equivalent rewrites of the source Expression."
  [rule-id effect]
  [rule-id (:kind effect) (:target effect)])

(defn canonicalise
  "Plan-2 stub. The Allium analyzer (Plan 2) replaces this body with the
   §3.8.4 four-pattern matcher: post.X.f = E ⇒ Write; post.X = T.created(…)
   ⇒ Create; not exists post.X ⇒ Destroy; emitted(E, args…) ⇒ Emit."
  [_expression]
  nil)

;; -- Malli schema -------------------------------------------------------------

(def ^:private EndpointPrimitive
  [:map [:case [:= :endpoint/primitive]] [:id :string]])

(def ^:private EndpointSubstrate
  [:map
   [:case [:= :endpoint/substrate]]
   [:container :string]
   [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]])

(def Endpoint
  [:multi {:dispatch :case}
   [:endpoint/primitive EndpointPrimitive]
   [:endpoint/substrate EndpointSubstrate]])

(def Effect
  [:map
   [:kind (into [:enum] effect-kinds)]
   [:target Endpoint]
   [:value {:optional true} e/Expression]
   [:source :string]])
