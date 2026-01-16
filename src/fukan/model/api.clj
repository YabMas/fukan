(ns fukan.model.api
  "Internal graph model construction from source code.
   Public API facade - re-exports from implementation modules."
  (:require [fukan.model.core :as core]
            [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]))

;; -----------------------------------------------------------------------------
;; Re-exported schemas from model.core
;; NOTE: No ^:schema metadata here - definitions live in model.core

;; Analysis schemas
(def NsDef core/NsDef)
(def VarDef core/VarDef)
(def VarUsage core/VarUsage)
(def NsUsage core/NsUsage)
(def AnalysisData core/AnalysisData)

;; Model schemas
(def NodeId core/NodeId)
(def NodeKind core/NodeKind)
(def Node core/Node)
(def Edge core/Edge)
(def Model core/Model)

;; -----------------------------------------------------------------------------
;; Main API

(defn build-model
  "Build the complete model from Clojure source code.

   Takes a path to source directory and returns a Model containing:
   - :nodes - {id -> node} for all folders, namespaces, vars, and schemas
   - :edges - vector of {:from :to} edges (var-level and ns-level combined)

   Internally runs clj-kondo analysis and builds the graph model.

   Edge aggregation (folder-level) and schema flow edges are computed
   on-demand by the view layer, not pre-computed here.

   Note: Single-child folder chains are pruned - the root of the tree is the
   first folder with multiple children or non-folder children."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [analysis (clj-lang/run-kondo src-path)]
    (build/build-model analysis {:type-nodes-fn clj-lang/build-schema-nodes})))
