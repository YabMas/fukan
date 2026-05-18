(ns fukan.model.primitives
  "Kernel primitives and value records (MODEL.md §3.1–3.5).

   Nine primitives:
     Container, Actor, Behaviour, Rule, Boundary, Operation, Intent, Clause, Event.

   Value records (substrate, not primitives):
     Field, Parameter, Definition, RuleBody.

   Every primitive carries :kind from `primitive-kinds`. The substrate does not
   validate referenced ids — that is the analyzer / build pipeline's job.

   Constructor arguments :id and :label are required for all primitives;
   the malli schemas enforce these at validate time. Constructors do not
   assert preconditions — they trust their callers (analyzers in Plan 2
   et seq.), in keeping with the substrate principle of validating only
   at system boundaries."
  (:require [fukan.model.type :as t]))

(def primitive-kinds
  #{:primitive/container :primitive/actor :primitive/behaviour
    :primitive/rule :primitive/boundary :primitive/operation
    :primitive/intent :primitive/clause :primitive/event})

;; -- Value records ------------------------------------------------------------

(defn make-field
  "Field substrate: name + Type + optionality. Identity is composed by
   field-identity using the owning Container's id."
  [name type-value optional?]
  {:name name, :type-ref type-value, :optional (boolean optional?)})

(defn field-identity
  "(container-id, Field.name) per MODEL.md §3.2."
  [container-id field]
  [container-id (:name field)])

(defn make-parameter
  "Parameter substrate: name + Type + optionality + ordinal."
  [name type-value optional? ordinal]
  {:name name, :type-ref type-value, :optional (boolean optional?), :ordinal ordinal})

(defn parameter-identity
  "(parent-id, Parameter.name) per MODEL.md §3.2. Parent is Operation or Event."
  [parent-id parameter]
  [parent-id (:name parameter)])

(defn make-definition
  "Definition is a typed `where:` binding inside Rule.body."
  [name expression]
  {:name name, :expression expression})

(defn definition-identity
  "(rule-id, Definition.name) per MODEL.md §3.2."
  [rule-id definition]
  [rule-id (:name definition)])

(defn make-rule-body
  "RuleBody bundles definitions + effects. No independent identity (reduces to
   host Rule's id)."
  ([] (make-rule-body [] []))
  ([definitions effects]
   {:definitions (vec definitions), :effects (vec effects)}))

;; -- Primitives ---------------------------------------------------------------

(defn- with-kind [kind m] (assoc m :kind kind))

(defn make-container
  "Container substrate. All faces optional except :id and :label."
  [{:keys [id label description intent children fields events behaviour boundary]}]
  (with-kind :primitive/container
    (cond-> {:id id, :label label}
      description (assoc :description description)
      intent      (assoc :intent intent)
      children    (assoc :children (set children))
      fields      (assoc :fields (vec fields))
      events      (assoc :events (set events))
      behaviour   (assoc :behaviour behaviour)
      boundary    (assoc :boundary boundary))))

(defn make-actor
  [{:keys [id label description]}]
  (with-kind :primitive/actor
    (cond-> {:id id, :label label}
      description (assoc :description description))))

(defn make-behaviour
  [{:keys [id label rules intent]}]
  (with-kind :primitive/behaviour
    (cond-> {:id id, :label label, :rules (vec rules)}
      intent (assoc :intent intent))))

(defn make-rule
  [{:keys [id label description intent body]}]
  (with-kind :primitive/rule
    (cond-> {:id id, :label label}
      description (assoc :description description)
      intent      (assoc :intent intent)
      body        (assoc :body body))))

(defn make-boundary
  [{:keys [id label operations intent]}]
  (with-kind :primitive/boundary
    (cond-> {:id id, :label label, :operations (vec operations)}
      intent (assoc :intent intent))))

(defn make-operation
  [{:keys [id label description parameters return-type intent]}]
  (with-kind :primitive/operation
    (cond-> {:id id, :label label, :parameters (vec parameters)}
      description (assoc :description description)
      return-type (assoc :return-type return-type)
      intent      (assoc :intent intent))))

(defn make-intent
  "Intent substrate. Singular per host (Container, Behaviour, Rule, Boundary,
   Operation). Carries `clauses` (prose claims) and `assertions` (Bool
   Expressions); label is optional and used for tool addressability."
  [{:keys [id label clauses assertions]}]
  (with-kind :primitive/intent
    (cond-> {:id id, :clauses (vec clauses), :assertions (vec assertions)}
      label (assoc :label label))))

(defn make-clause
  [{:keys [id label body]}]
  (with-kind :primitive/clause
    (cond-> {:id id, :body body}
      label (assoc :label label))))

(defn make-event
  [{:keys [id label description parameters]}]
  (with-kind :primitive/event
    (cond-> {:id id, :label label, :parameters (vec parameters)}
      description (assoc :description description))))

;; -- Malli schemas ------------------------------------------------------------

(def Field
  [:map
   [:name :string]
   [:type-ref t/Type]
   [:optional :boolean]])

(def Parameter
  [:map
   [:name :string]
   [:type-ref t/Type]
   [:optional :boolean]
   [:ordinal :int]])

(def Intent
  [:map
   [:kind [:= :primitive/intent]]
   [:id :string]
   [:label {:optional true} :string]
   [:clauses [:vector :any]]                   ;; clause values; closed in Clause schema
   [:assertions [:vector :any]]])              ;; Expression values; closed in expression.clj

(def Clause
  [:map
   [:kind [:= :primitive/clause]]
   [:id :string]
   [:label {:optional true} :string]
   [:body :string]])

(def Behaviour
  [:map
   [:kind [:= :primitive/behaviour]]
   [:id :string]
   [:label :string]
   [:rules [:vector :string]]
   [:intent {:optional true} Intent]])

(def Boundary
  [:map
   [:kind [:= :primitive/boundary]]
   [:id :string]
   [:label :string]
   [:operations [:vector :string]]
   [:intent {:optional true} Intent]])

(def Container
  [:map
   [:kind [:= :primitive/container]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:intent {:optional true} Intent]
   [:children {:optional true} [:set :string]]
   [:fields {:optional true} [:vector Field]]
   [:events {:optional true} [:set :string]]
   [:behaviour {:optional true} Behaviour]
   [:boundary  {:optional true} Boundary]])

(def Actor
  [:map
   [:kind [:= :primitive/actor]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]])

(def Rule
  [:map
   [:kind [:= :primitive/rule]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:intent {:optional true} Intent]
   [:body {:optional true}
    [:map
     [:definitions [:vector :any]]             ;; Definition values
     [:effects [:vector :any]]]]])             ;; Effect values

(def Operation
  [:map
   [:kind [:= :primitive/operation]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:parameters [:vector Parameter]]
   [:return-type {:optional true} t/Type]
   [:intent {:optional true} Intent]])

(def Event
  [:map
   [:kind [:= :primitive/event]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:parameters [:vector Parameter]]])

(def Primitive
  [:multi {:dispatch :kind}
   [:primitive/container Container]
   [:primitive/actor     Actor]
   [:primitive/behaviour Behaviour]
   [:primitive/rule      Rule]
   [:primitive/boundary  Boundary]
   [:primitive/operation Operation]
   [:primitive/intent    Intent]
   [:primitive/clause    Clause]
   [:primitive/event     Event]])
