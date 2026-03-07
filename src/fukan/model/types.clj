(ns fukan.model.types
  "Language-agnostic type expression IR shared across analyzers,
   projection, and views. Cross-cutting type schemas that don't
   belong to any single submodule.")

;; -----------------------------------------------------------------------------
;; Analysis Schemas
;;
;; These schemas define the normalized format that any language analyzer
;; should produce. Language-specific analyzers convert their native format
;; to these generic structures.

(def ^:schema ModuleDef
  [:map {:description "A module (namespace/scope) definition."}
   [:name :symbol]
   [:filename :string]
   [:doc {:optional true} [:maybe :string]]])

(def ^:schema SymbolDef
  [:map {:description "A symbol (function/variable) definition."}
   [:module :symbol]
   [:name :symbol]
   [:filename :string]
   [:row :int]
   [:doc {:optional true} [:maybe :string]]
   [:private {:optional true} :boolean]])

(def ^:schema SymbolRef
  [:map {:description "A reference from one symbol to another."}
   [:from {:description "Module containing the call site"} :symbol]
   [:from-symbol {:optional true :description "Symbol at the call site, nil if top-level"} :symbol]
   [:to {:description "Module containing the target symbol"} :symbol]
   [:name {:description "Name of the referenced symbol"} :symbol]])

(def ^:schema ModuleImport
  [:map {:description "A module require/import relationship."}
   [:from :symbol]
   [:to :symbol]
   [:filename :string]])

(def ^:schema CodeAnalysis
  [:map {:description "The normalized output format from any language analyzer."}
   [:module-definitions [:vector :ModuleDef]]
   [:symbol-definitions [:vector :SymbolDef]]
   [:symbol-references [:vector :SymbolRef]]
   [:module-imports {:optional true} [:vector :ModuleImport]]])

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
;; Shared Domain Types

(def ^:schema FilePath
  [:and {:description "Filesystem path: forward-slash separated, non-empty. May be absolute (/...) or project-relative (src/...)."}
   [:string {:min 1}]
   [:re {:error/message "must be a valid file path (forward-slash separated, no backslashes, no leading/trailing whitespace)"}
    #"^[^\s\\].*[^\s]$"]])

(def ^:schema SourceAnalyzer
  [:=> {:description "A language analyzer: given a source directory path, produces an AnalysisResult."}
   [:cat :FilePath] :AnalysisResult])

;; -----------------------------------------------------------------------------
;; Model Schemas

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
    [:doc {:optional true} [:maybe :string]]]])

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
  [:enum {:description "Discriminates edge semantics: function calls vs schema type references."}
   :function-call :schema-reference])

(def ^:schema Edge
  [:map {:description "A directed dependency between two nodes."}
   [:from {:description "Node ID of the caller/referencer"} :NodeId]
   [:to {:description "Node ID of the callee/referenced entity"} :NodeId]
   [:kind {:description "Edge kind: function-call or schema-reference."} :EdgeKind]])

(def ^:schema Model
  [:map {:description "The complete graph model of a codebase: all entity nodes and their directed dependency edges."}
   [:nodes [:map-of :NodeId :Node]]
   [:edges [:vector :Edge]]])

;; -----------------------------------------------------------------------------
;; AnalysisResult

(def ^:schema AnalysisResult
  [:map {:description "A language analysis result: pre-built nodes and edges ready for the build pipeline. Each language analyzer produces an AnalysisResult; results are merged before calling build-model."}
   [:source-files {:description "File paths for folder hierarchy construction."} [:vector :FilePath]]
   [:nodes {:description "Pre-built nodes. Module nodes should have :parent nil and :filename in :data for folder parenting."} [:map-of :NodeId :Node]]
   [:edges {:description "Pre-built edges between nodes."} [:vector :Edge]]])
