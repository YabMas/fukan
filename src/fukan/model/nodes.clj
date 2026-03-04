(ns fukan.model.nodes
  "Construction helpers for building model nodes and edges from
   normalized analysis data. Used by language analyzers to produce
   AnalysisResult values."
  (:require [clojure.string :as str]))

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
