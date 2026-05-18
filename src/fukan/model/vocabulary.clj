(ns fukan.model.vocabulary
  "Vocabulary mechanism — TagDefinition, TagApplication, PredicateRegistration,
   RendererRegistration (MODEL.md §5.2–§5.5).

   Plan 1 ships the data shapes and v0 inheritance semantics (V9): payload-
   schema extension ✅, tag-presence implication ✅, field-override ❌,
   multi-parent ❌. Concrete predicate-language semantics arrive in Plan 4;
   renderer treatments arrive in Plan 6."
  (:require [fukan.model.type :as t]))

;; -- TagDefinition ------------------------------------------------------------

(defn make-tag-definition
  [{:keys [namespace name applies-to payload-schema parent-tag relational]}]
  (cond-> {:namespace namespace, :name name, :applies-to applies-to}
    payload-schema (assoc :payload-schema payload-schema)
    parent-tag     (assoc :parent-tag parent-tag)
    relational     (assoc :relational relational)))

;; -- TagApplication -----------------------------------------------------------

(defn make-tag-application
  [{:keys [tag target payload]}]
  {:tag tag, :target target, :payload (or payload {})})

(defn- tag-ref-equal? [a b]
  (and (= (:namespace a) (:namespace b)) (= (:name a) (:name b))))

(defn- definitions-by-ref [registry]
  (into {} (map (juxt #(select-keys % [:namespace :name]) identity)
                (:tag-definitions registry))))

(defn- ancestor-chain
  "All tag-refs from `tag-ref` up through every :parent-tag."
  [defs-by-ref tag-ref]
  (loop [current tag-ref, acc []]
    (if (nil? current)
      acc
      (let [td (defs-by-ref current)]
        (recur (:parent-tag td) (conj acc current))))))

(defn has-tag-with-ancestors?
  "True iff `target-id` (a primitive id) carries `tag-ref` *or* any descendant
   tag whose ancestor chain includes `tag-ref` (V9 tag-presence implication).
   Plan 1 supports `:target/primitive` targets only; edge and substrate targets
   are not matched. Inheritance is followed transitively across :parent-tag
   links to any depth."
  [registry target-id tag-ref]
  (let [defs (definitions-by-ref registry)
        applications (->> (:tag-applications registry)
                          (filter (fn [ta]
                                    (let [tgt (:target ta)]
                                      (and (= :target/primitive (:case tgt))
                                           (= target-id (:id tgt)))))))
        applied-refs (map :tag applications)
        all-implied  (mapcat (fn [r] (ancestor-chain defs r)) applied-refs)]
    (boolean (some #(tag-ref-equal? % tag-ref) all-implied))))

;; -- PredicateRegistration ---------------------------------------------------

(defn make-predicate-registration
  [{:keys [namespace name severity kind scope message-template predicate applies-to]}]
  (cond-> {:namespace namespace, :name name
           :severity severity
           :kind kind
           :scope (or scope :scope/model)
           :message-template (or message-template "")
           :predicate predicate}
    applies-to (assoc :applies-to applies-to)))

;; -- RendererRegistration ----------------------------------------------------

(defn make-renderer-registration
  [{:keys [tag node-treatment sidebar-treatment edge-treatment layout-hint]}]
  (cond-> {:tag tag}
    node-treatment    (assoc :node-treatment node-treatment)
    sidebar-treatment (assoc :sidebar-treatment sidebar-treatment)
    edge-treatment    (assoc :edge-treatment edge-treatment)
    layout-hint       (assoc :layout-hint layout-hint)))

;; -- Malli schemas ------------------------------------------------------------

(def TagRef
  [:map [:namespace :string] [:name :string]])

(def TagTarget
  [:multi {:dispatch :case}
   [:target/primitive [:map [:case [:= :target/primitive]] [:id :string]]]
   [:target/edge      [:map [:case [:= :target/edge]] [:edge-identity :any]]]
   [:target/substrate [:map [:case [:= :target/substrate]]
                       [:container :string]
                       [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]]]])

(def RelationalSpec
  [:map
   [:endpoints [:vector :string]]
   [:symmetry [:enum :directed :symmetric]]
   [:canonical-side {:optional true} :string]
   [:coherence-query {:optional true} :any]])

(def AppliesTo
  [:enum :target/container :target/actor :target/behaviour :target/rule
   :target/boundary :target/operation :target/intent :target/clause :target/event
   :target/edge :target/substrate])

(def TagDefinition
  [:map
   [:namespace :string]
   [:name :string]
   [:applies-to AppliesTo]
   [:payload-schema {:optional true} t/Type]
   [:parent-tag {:optional true} TagRef]
   [:relational {:optional true} RelationalSpec]])

(def TagApplication
  [:map
   [:tag TagRef]
   [:target TagTarget]
   [:payload :map]])

(def PredicateRegistration
  [:map
   [:namespace :string]
   [:name :string]
   [:severity [:enum :error :warning]]
   [:kind :string]
   [:scope [:enum :scope/model :scope/tag]]
   [:message-template :string]
   [:predicate :any]
   [:applies-to {:optional true} TagRef]])

(def RendererRegistration
  [:map
   [:tag TagRef]
   [:node-treatment    {:optional true} :any]
   [:sidebar-treatment {:optional true} :any]
   [:edge-treatment    {:optional true} :any]
   [:layout-hint       {:optional true} :any]])
