(ns fukan.model.nodes
  "Graph primitives (Node, Edge, NodeId) and analysis input shapes
   (CodeAnalysis, ModuleDef, SymbolDef). Construction helpers for
   building model nodes and edges from normalized analysis data."
  (:require [clojure.string :as str]
            [fukan.model.types]))

;; Require model.types so TypeExpr/FunctionSignature are registered
;; before schemas here reference them via keyword.

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
  [:enum {:description "Discriminates edge semantics: function calls, polymorphic dispatch, or schema type references."}
   :function-call :dispatches :schema-reference])

(def ^:schema Edge
  [:map {:description "A directed dependency between two nodes."}
   [:from {:description "Node ID of the caller/referencer"} :NodeId]
   [:to {:description "Node ID of the callee/referenced entity"} :NodeId]
   [:kind {:description "Edge kind: function-call or schema-reference."} :EdgeKind]])

;; -----------------------------------------------------------------------------
;; Internal helpers

(defn- file-to-folder
  "Get the folder path for a file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (when (> (count parts) 1)
      (str/join "/" (butlast parts)))))

;; -----------------------------------------------------------------------------
;; Public API

(defn build-module-nodes
  "Build module nodes from module definitions.
   Labels are the short form (last segment of the module name).
   Returns {:nodes {id -> node}, :index {module-sym -> id}}."
  {:malli/schema [:=> [:cat [:vector :ModuleDef] [:map-of :string :NodeId]]
                  [:map [:nodes [:map-of :NodeId :Node]] [:index [:map-of :symbol :NodeId]]]]}
  [module-defs folder-index]
  (reduce (fn [acc {:keys [name filename doc]}]
            (let [id (str name)
                  full-name (str name)
                  short-label (let [dot-idx (str/last-index-of full-name ".")]
                                (if dot-idx
                                  (subs full-name (inc dot-idx))
                                  full-name))
                  folder-path (file-to-folder filename)
                  parent-id (get folder-index folder-path)
                  node {:id id
                        :kind :module
                        :label short-label
                        :parent parent-id
                        :children #{}
                        :data {:kind :module
                               :doc doc}}]
              (-> acc
                  (assoc-in [:nodes id] node)
                  (assoc-in [:index name] id))))
          {:nodes {} :index {}}
          module-defs))

(defn build-symbol-nodes
  "Build symbol nodes from symbol definitions.
   Returns {:nodes {id -> node}, :index {[module-sym symbol-name] -> id}}."
  {:malli/schema [:=> [:cat [:vector :SymbolDef] [:map-of :symbol :NodeId]]
                  [:map [:nodes [:map-of :NodeId :Node]] [:index [:map-of [:tuple :symbol :symbol] :NodeId]]]]}
  [symbol-defs module-index]
  (reduce (fn [acc {:keys [module name doc private]}]
            (let [id (str module "/" name)
                  parent-id (get module-index module)
                  node {:id id
                        :kind :function
                        :label (str name)
                        :parent parent-id
                        :children #{}
                        :data {:kind :function
                               :doc doc
                               :private? (boolean private)}}]
              (-> acc
                  (assoc-in [:nodes id] node)
                  (assoc-in [:index [module name]] id))))
          {:nodes {} :index {}}
          symbol-defs))

(defn build-reference-edges
  "Build leaf-to-leaf edges from actual call relationships.

   Creates edges for each symbol reference where both endpoints resolve to
   leaf nodes. References without a from-symbol (top-level or anonymous) are
   skipped — they would produce module-to-leaf edges violating LeafEdges.

   Returns a vector of {:from node-id, :to node-id} edges."
  {:malli/schema [:=> [:cat :CodeAnalysis [:map-of [:tuple :symbol :symbol] :NodeId] [:map-of :symbol :NodeId]] [:vector :Edge]]}
  [analysis symbol-index _module-index]
  (let [symbol-refs (:symbol-references analysis)]
    (->> symbol-refs
         (keep (fn [{:keys [from from-symbol to name]}]
                 (when (and from-symbol
                            (get symbol-index [from from-symbol]))
                   (let [from-id (get symbol-index [from from-symbol])
                         to-id   (get symbol-index [to name])]
                     (when (and to-id (not= from-id to-id))
                       {:from from-id :to to-id :kind :function-call})))))
         (into #{})
         (vec))))
