(ns fukan.model.relations
  "The thirteen kernel relations and edge machinery (MODEL.md §4).

   Endpoints are PrimitiveRef | SubstrateAddress. Edges are values; identity
   is (from, to, kind, identifying-metadata). Multi-edges are allowed iff the
   identifying-metadata subset differs.

   Per-relation identifying-metadata slots per §4.4."
  (:require [fukan.model.expression :as e]))

(def relation-kinds
  #{:relation/triggers :relation/observes :relation/reads
    :relation/writes :relation/creates :relation/destroys
    :relation/emits :relation/realises :relation/specialises
    :relation/uses :relation/exposes :relation/provides
    :relation/projects})

(def ^:private identifying-slots-table
  {:relation/triggers    #{}
   :relation/observes    #{:condition}
   :relation/reads       #{:condition}
   :relation/writes      #{:condition :scope}
   :relation/creates     #{:condition :scope}
   :relation/destroys    #{:condition :scope}
   :relation/emits       #{:condition :scope}
   :relation/realises    #{}
   :relation/specialises #{}
   :relation/uses        #{}
   :relation/exposes     #{}
   :relation/provides    #{}
   :relation/projects    #{:projection-kind}})

(defn identifying-slots
  "Per-relation identifying-metadata slot names, per MODEL.md §4.4."
  [relation-kind]
  (identifying-slots-table relation-kind))

(defn primitive-ref [id] {:case :endpoint/primitive, :id id})

(defn substrate-address [container-id path]
  {:case :endpoint/substrate, :container container-id, :path (vec path)})

(defn make-edge
  "Construct a kernel edge.

     :kind - relation kind keyword (see relation-kinds)
     :from - Endpoint
     :to   - Endpoint
     :metadata (optional) - map; identifying slots per §4.4 plus any
       non-identifying methodology metadata"
  ([kind from to] (make-edge kind from to {}))
  ([kind from to metadata]
   (assert (relation-kinds kind) (str "Unknown relation: " kind))
   (merge {:kind kind, :from from, :to to} metadata)))

(defn edge-identity
  "(from, to, kind, identifying-subset) per §4.4. The identifying subset is
   the per-relation slots from §4.4; non-identifying slots are dropped."
  [edge]
  (let [slots (identifying-slots-table (:kind edge))]
    [(:from edge)
     (:to edge)
     (:kind edge)
     (select-keys edge slots)]))

;; -- Malli schemas ------------------------------------------------------------

(def ^:private EndpointPrimitive
  [:map [:case [:= :endpoint/primitive]] [:id :string]])

(def ^:private EndpointSubstrate
  [:map [:case [:= :endpoint/substrate]]
   [:container :string]
   [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]])

(def Endpoint
  [:multi {:dispatch :case}
   [:endpoint/primitive EndpointPrimitive]
   [:endpoint/substrate EndpointSubstrate]])

(def projection-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/schema
    :projection-kind/test})

(def Edge
  [:map
   [:kind (into [:enum] relation-kinds)]
   [:from Endpoint]
   [:to Endpoint]
   [:condition       {:optional true} e/Expression]
   [:scope           {:optional true} e/Expression]
   [:projection-kind {:optional true} (into [:enum] projection-kinds)]])
