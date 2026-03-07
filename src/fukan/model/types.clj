(ns fukan.model.types
  "Language-agnostic type expression IR shared across analyzers,
   projection, and views. Cross-cutting type schemas that don't
   belong to any single submodule.")

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

(def ^:schema Model
  [:map {:description "The complete graph model of a codebase: all entity nodes and their directed dependency edges."}
   [:nodes [:map-of :NodeId :Node]]
   [:edges [:vector :Edge]]])

(def ^:schema AnalysisResult
  [:map {:description "A language analysis result: pre-built nodes and edges ready for the build pipeline. Each language analyzer produces an AnalysisResult; results are merged before calling build-model."}
   [:source-files {:description "File paths for folder hierarchy construction."} [:vector :FilePath]]
   [:nodes {:description "Pre-built nodes. Module nodes should have :parent nil and :filename in :data for folder parenting."} [:map-of :NodeId :Node]]
   [:edges {:description "Pre-built edges between nodes."} [:vector :Edge]]])
