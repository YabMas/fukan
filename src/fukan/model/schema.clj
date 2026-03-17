(ns fukan.model.schema
  "The global model schema: the data shapes that all downstream consumers
   operate on. Type expressions, graph primitives (Node, Edge, NodeId),
   and the top-level Model container.")

;; -----------------------------------------------------------------------------
;; Type Expression IR

(def ^:schema TypeExpr
  [:multi {:dispatch :tag
           :description "Generic, language-agnostic type expression. Produced by language analyzers at build time, consumed by projection and views. Every variant may carry optional :description. Recursive: nested types are themselves TypeExpr values."}
   [:ref       [:map [:tag [:= :ref]]       [:name :keyword] [:description {:optional true} :string]]]
   [:primitive [:map [:tag [:= :primitive]] [:name :string]   [:description {:optional true} :string]]]
   [:map       [:map [:tag [:= :map]]
                [:entries [:vector [:map
                                    [:key :string]
                                    [:optional :boolean]
                                    [:type :TypeExpr]
                                    [:description {:optional true} [:maybe :string]]]]]
                [:description {:optional true} :string]]]
   [:map-of    [:map [:tag [:= :map-of]]    [:key-type :TypeExpr] [:value-type :TypeExpr] [:description {:optional true} :string]]]
   [:vector    [:map [:tag [:= :vector]]    [:element :TypeExpr]  [:description {:optional true} :string]]]
   [:set       [:map [:tag [:= :set]]       [:element :TypeExpr]  [:description {:optional true} :string]]]
   [:maybe     [:map [:tag [:= :maybe]]     [:inner :TypeExpr]    [:description {:optional true} :string]]]
   [:or        [:map [:tag [:= :or]]        [:variants [:vector :TypeExpr]] [:description {:optional true} :string]]]
   [:and       [:map [:tag [:= :and]]       [:types [:vector :TypeExpr]]    [:description {:optional true} :string]]]
   [:enum      [:map [:tag [:= :enum]]      [:values :any]                  [:description {:optional true} :string]]]
   [:tuple     [:map [:tag [:= :tuple]]     [:elements [:vector :TypeExpr]] [:description {:optional true} :string]]]
   [:fn        [:map [:tag [:= :fn]]        [:inputs [:vector :TypeExpr]] [:output :TypeExpr] [:description {:optional true} :string]]]
   [:predicate [:map [:tag [:= :predicate]] [:description {:optional true} :string]]]
   [:unknown   [:map [:tag [:= :unknown]]   [:original :string] [:description {:optional true} :string]]]])

(def ^:schema FunctionSignature
  [:map {:description "The type contract of a function: ordered input types and a return type."}
   [:inputs [:vector :TypeExpr]]
   [:output :TypeExpr]])

;; -----------------------------------------------------------------------------
;; Graph Schemas

(def ^:schema NodeId
  [:string {:description "Unique string identifier for a node in the model graph."}])

(def ^:schema NodeKind
  [:enum {:description "Structural kind: module (directory/namespace), function (var), or schema definition."}
   :module :function :schema])

(def ^:schema BoundaryFn
  [:map {:description "A public function entry in a module boundary."}
   [:name :symbol]
   [:id {:optional true, :description "Node ID of the boundary function."} :NodeId]
   [:schema {:optional true, :description "Structured function signature: inputs and output types."}
    :FunctionSignature]
   [:doc {:optional true} :string]])

(def ^:schema Boundary
  [:map {:description "A module's external boundary: the functions, schemas, and guarantees that define its public contract."}
   [:description {:optional true} :string]
   [:functions {:optional true} [:vector :BoundaryFn]]
   [:schemas {:optional true} [:vector :keyword]]
   [:guarantees {:optional true} [:vector :string]]])

(def ^:schema NodeData
  [:or {:description "Kind-specific properties attached to a node, discriminated by :kind."}
   ;; Module data (directory or namespace)
   [:map {:description "Module node data: documentation and optional boundary."}
    [:kind [:= :module]]
    [:doc {:optional true} [:maybe :string]]
    [:boundary :Boundary]]
   ;; Function data (var definition)
   [:map {:description "Function node data: documentation, visibility, and optional type signature."}
    [:kind [:= :function]]
    [:doc {:optional true} [:maybe :string]]
    [:private? {:optional true} :boolean]
    [:signature {:optional true, :description "Structured function signature: inputs and output types."}
     :FunctionSignature]]
   ;; Schema data (schema definition)
   [:map {:description "Schema node data: the TypeExpr form and its keyword key."}
    [:kind [:= :schema]]
    [:schema-key :keyword]
    [:schema {:description "TypeExpr representation of the schema."} :TypeExpr]
    [:doc {:optional true} [:maybe :string]]
    [:private? {:optional true} :boolean]]])

(def ^:schema Node
  [:map {:description "An entity in the system model: module, function, or schema definition."}
   [:id :NodeId]
   [:kind :NodeKind]
   [:label :string]
   [:description {:optional true} :string]
   [:parent {:optional true} [:maybe :NodeId]]
   [:children [:set :NodeId]]
   [:data {:optional true} :NodeData]])

(def ^:schema EdgeKind
  [:enum {:description "Discriminates edge semantics: function calls, polymorphic dispatch, or schema type references."}
   :function-call :dispatches :schema-reference])

(def ^:schema Edge
  [:map {:description "A directed dependency between two nodes."}
   [:from {:description "Node ID of the caller/referencer"} :NodeId]
   [:to {:description "Node ID of the callee/referenced entity"} :NodeId]
   [:kind {:description "Edge kind: function-call, dispatches, or schema-reference."} :EdgeKind]])

;; -----------------------------------------------------------------------------
;; Top-level Model

(def ^:schema Model
  [:map {:description "The complete graph model of a codebase: all entity nodes and their directed dependency edges."}
   [:nodes [:map-of :NodeId :Node]]
   [:edges [:vector :Edge]]])
