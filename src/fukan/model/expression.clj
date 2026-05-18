(ns fukan.model.expression
  "Expression sub-substrate (MODEL.md §3.8.1, §3.8.5–§3.8.7).

   One calculus, three callsites:
     - Rule bodies (TwoState environment)
     - Intent assertions on any host (OneState env for Container/Behaviour/
       Boundary/Operation; TwoState for Rule)
     - §6 constraint predicates (ModelIntrospection env, Plan 4)

   Expression record: { label?, form }. Identity is structural over :form;
   label? is addressability-only (parallels Clause's label? per K15a).

   Type-checking and Environment-bound evaluation are deferred to Plan 4 (the
   constraint engine). This module defines the data shape and structural id."
  (:require [fukan.model.type :as t]))

;; -- Kernel-core operator vocabulary -----------------------------------------

(def core-operators
  "Kernel-committed core operators per MODEL.md §3.8.1. Methodologies add
   operators in Plan 4 via the registration shape parallel to §5.3."
  #{"+" "-" "*" "/"
    "=" "!=" "<" "<=" ">" ">="
    "and" "or" "not"
    "in" "contains"
    "is-present" "is-absent"})

;; -- Form constructors -------------------------------------------------------

(defn- expr
  ([form] {:form form})
  ([label form] (cond-> {:form form} (some? label) (assoc :label label))))

(defn make-var
  ([name] (expr {:case :expr/var, :name name}))
  ([label name] (expr label {:case :expr/var, :name name})))

(defn make-ref
  ([ref-target] (expr {:case :expr/ref, :target ref-target}))
  ([label ref-target] (expr label {:case :expr/ref, :target ref-target})))

(defn make-lit
  ([type-value value] (expr {:case :expr/lit, :type type-value, :value value}))
  ([label type-value value] (expr label {:case :expr/lit, :type type-value, :value value})))

(defn make-apply
  ([op args] (expr {:case :expr/apply, :op op, :args (vec args)}))
  ([label op args] (expr label {:case :expr/apply, :op op, :args (vec args)})))

(defn make-let
  ([name source body]
   (expr {:case :expr/let, :name name, :source source, :body body}))
  ([label name source body]
   (expr label {:case :expr/let, :name name, :source source, :body body})))

(defn make-if
  ([cond then else]
   (expr {:case :expr/if, :cond cond, :then then, :else else}))
  ([label cond then else]
   (expr label {:case :expr/if, :cond cond, :then then, :else else})))

(defn make-forall
  ([var-name source body]
   (expr {:case :expr/forall, :var var-name, :source source, :body body}))
  ([label var-name source body]
   (expr label {:case :expr/forall, :var var-name, :source source, :body body})))

(defn make-exists
  ([var-name source body]
   (expr {:case :expr/exists, :var var-name, :source source, :body body}))
  ([label var-name source body]
   (expr label {:case :expr/exists, :var var-name, :source source, :body body})))

(def aggregate-kinds #{:count :sum :min :max})

(defn make-aggregate
  ([kind source projection]
   (assert (aggregate-kinds kind) (str "Unknown aggregate kind: " kind))
   (expr {:case :expr/aggregate, :kind kind, :source source, :projection projection}))
  ([label kind source projection]
   (assert (aggregate-kinds kind) (str "Unknown aggregate kind: " kind))
   (expr label {:case :expr/aggregate, :kind kind, :source source, :projection projection})))

(defn make-match-arm
  "MatchArm: { pattern: TypePattern, body: Expression }."
  [pattern body]
  {:pattern pattern, :body body})

(defn make-match
  ([scrutinee arms]
   (expr {:case :expr/match, :scrutinee scrutinee, :arms (vec arms)}))
  ([label scrutinee arms]
   (expr label {:case :expr/match, :scrutinee scrutinee, :arms (vec arms)})))

;; -- Structural identity ------------------------------------------------------

(defn- strip-labels
  "Recursively drop :label from this Expression and every nested Expression."
  [x]
  (cond
    (and (map? x) (contains? x :form))
    {:form (strip-labels (:form x))}

    (map? x)
    (into {} (map (fn [[k v]] [k (strip-labels v)]) x))

    (vector? x)
    (mapv strip-labels x)

    :else x))

(defn expression-identity
  "Structural shape of :form, recursively stripped of :label slots. Two
   Expressions with the same identity are the same Expression value.

   Output shape: a nested map whose outermost layer is the unwrapped
   ExpressionForm (the value of `:form`), but whose nested Expression-typed
   slots (`:args` elements, `:source`, `:body`, `:then`, `:else`,
   `:scrutinee`, `:projection`, MatchArm `:body`) retain their `{:form …}`
   envelope (minus `:label`). The wrapping is preserved symmetrically at
   every depth — identity equality still holds, but consumers walking the
   structural value should expect Expression-wrapped sub-trees, not bare
   ExpressionForms."
  [expression]
  (strip-labels (:form expression)))

;; -- Environment --------------------------------------------------------------

(defn make-environment-onestate [bindings]
  {:case :env/onestate, :bindings bindings})

(defn make-environment-twostate [pre post params]
  {:case :env/twostate, :pre pre, :post post, :params params})

(defn make-environment-model-introspection [bindings]
  {:case :env/model-introspection, :bindings bindings})

;; -- Malli schemas ------------------------------------------------------------

(def Bindings [:map-of :string t/Type])

(def Environment
  [:multi {:dispatch :case}
   [:env/onestate
    [:map [:case [:= :env/onestate]] [:bindings Bindings]]]
   [:env/twostate
    [:map [:case [:= :env/twostate]]
     [:pre Bindings] [:post Bindings] [:params Bindings]]]
   [:env/model-introspection
    [:map [:case [:= :env/model-introspection]] [:bindings Bindings]]]])

(def Expression
  "Recursive schema; uses a local registry."
  [:schema
   {:registry
    {::Expression
     [:map
      [:label {:optional true} :string]
      [:form [:ref ::ExpressionForm]]]
     ::ExpressionForm
     [:multi {:dispatch :case}
      [:expr/var       [:map [:case [:= :expr/var]] [:name :string]]]
      [:expr/ref       [:map [:case [:= :expr/ref]] [:target :any]]]
      [:expr/lit       [:map [:case [:= :expr/lit]] [:type t/Type] [:value :any]]]
      [:expr/apply     [:map [:case [:= :expr/apply]] [:op :string] [:args [:vector [:ref ::Expression]]]]]
      [:expr/let       [:map [:case [:= :expr/let]]
                        [:name :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/if        [:map [:case [:= :expr/if]]
                        [:cond [:ref ::Expression]]
                        [:then [:ref ::Expression]]
                        [:else [:ref ::Expression]]]]
      [:expr/forall    [:map [:case [:= :expr/forall]]
                        [:var :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/exists    [:map [:case [:= :expr/exists]]
                        [:var :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/aggregate [:map [:case [:= :expr/aggregate]]
                        [:kind (into [:enum] aggregate-kinds)]
                        [:source [:ref ::Expression]]
                        [:projection [:ref ::Expression]]]]
      [:expr/match     [:map [:case [:= :expr/match]]
                        [:scrutinee [:ref ::Expression]]
                        [:arms [:vector [:map
                                         [:pattern :any]
                                         [:body [:ref ::Expression]]]]]]]]}}
   [:ref ::Expression]])
