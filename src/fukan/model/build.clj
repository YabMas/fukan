(ns fukan.model.build
  "Language-agnostic model construction pipeline.
   Builds the internal graph model from analysis data."
  (:require [clojure.string :as str]
            [fukan.model.core :as core]))

;; -----------------------------------------------------------------------------
;; Folder node construction

(defn- parent-dirs
  "Given a file path, return all parent directory paths.
   e.g., 'src/foo/bar/baz.clj' -> ['src' 'src/foo' 'src/foo/bar']"
  [filepath]
  (let [parts (str/split filepath #"/")
        dir-parts (butlast parts)] ; drop the filename
    (when (seq dir-parts)
      (reductions (fn [acc part] (str acc "/" part))
                  (first dir-parts)
                  (rest dir-parts)))))

(defn- extract-all-dirs
  "Extract all unique directory paths from namespace definitions."
  [ns-defs]
  (->> ns-defs
       (mapcat (fn [{:keys [filename]}]
                 (parent-dirs filename)))
       (into #{})))

(defn- dir-parent
  "Get the parent directory path, or nil for top-level."
  [dir-path]
  (let [idx (str/last-index-of dir-path "/")]
    (when idx
      (subs dir-path 0 idx))))

(defn- build-folder-nodes
  "Build folder nodes from namespace definitions.
   Returns {:nodes {id -> node}, :index {path -> id}}."
  [ns-defs]
  (let [all-dirs (extract-all-dirs ns-defs)]
    (reduce (fn [acc dir-path]
              (let [id (core/gen-id)
                    label (last (str/split dir-path #"/"))
                    parent-path (dir-parent dir-path)
                    parent-id (get-in acc [:index parent-path])
                    node {:id id
                          :kind :folder
                          :label label
                          :parent parent-id
                          :children #{}
                          :data {:path dir-path}}]
                (-> acc
                    (assoc-in [:nodes id] node)
                    (assoc-in [:index dir-path] id))))
            {:nodes {} :index {}}
            ;; Sort dirs so parents are processed first
            (sort-by count all-dirs))))

;; -----------------------------------------------------------------------------
;; Namespace node construction

(defn- file-to-folder
  "Get the folder path for a file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (when (> (count parts) 1)
      (str/join "/" (butlast parts)))))

(defn- build-namespace-nodes
  "Build namespace nodes from namespace definitions.
   Returns {:nodes {id -> node}, :index {ns-sym -> id}}."
  [ns-defs folder-index]
  (reduce (fn [acc {:keys [name filename doc]}]
            (let [id (core/gen-id)
                  folder-path (file-to-folder filename)
                  parent-id (get folder-index folder-path)
                  node {:id id
                        :kind :namespace
                        :label (str name)
                        :parent parent-id
                        :children #{}
                        :data {:ns-sym name
                               :filename filename
                               :doc doc}}]
              (-> acc
                  (assoc-in [:nodes id] node)
                  (assoc-in [:index name] id))))
          {:nodes {} :index {}}
          ns-defs))

;; -----------------------------------------------------------------------------
;; Var node construction

(defn- build-var-nodes
  "Build var nodes from var definitions.
   Returns {:nodes {id -> node}, :index {[ns-sym var-name] -> id}}."
  [var-defs ns-index]
  (reduce (fn [acc {:keys [ns name filename row doc private]}]
            (let [id (core/gen-id)
                  parent-id (get ns-index ns)
                  node {:id id
                        :kind :var
                        :label (str name)
                        :parent parent-id
                        :children #{}
                        :data {:var-sym name
                               :ns-sym ns
                               :filename filename
                               :row row
                               :doc doc
                               :private? (boolean private)}}]
              (-> acc
                  (assoc-in [:nodes id] node)
                  (assoc-in [:index [ns name]] id))))
          {:nodes {} :index {}}
          var-defs))

;; -----------------------------------------------------------------------------
;; Edge construction

(defn- build-edges
  "Build raw edges from actual call relationships.

   Creates edges for each var usage where the target var is defined in the codebase.
   - When from-var is present: creates var-to-var edge
   - When from-var is nil (top-level or anonymous): creates ns-to-var edge

   Returns a vector of {:from node-id, :to node-id} edges."
  [analysis var-index ns-index]
  (let [var-usages (:var-usages analysis)
        known-ns (set (keys ns-index))]
    (->> var-usages
         (keep (fn [{:keys [from from-var to name]}]
                 (when-let [to-id (get var-index [to name])]
                   (let [from-id (if from-var
                                   ;; Normal case: var-to-var edge
                                   (get var-index [from from-var])
                                   ;; Top-level/anonymous: ns-to-var edge
                                   (when (contains? known-ns from)
                                     (get ns-index from)))]
                     (when (and from-id (not= from-id to-id))
                       {:from from-id :to to-id})))))
         (into #{})
         (vec))))

(defn- build-ns-edges
  "Build namespace-to-namespace edges from require relationships.
   Creates edges for each namespace require where both namespaces
   are defined in the codebase.

   Returns a vector of {:from ns-id, :to ns-id, :kind :ns-require} edges."
  [analysis ns-index]
  (let [ns-usages (:namespace-usages analysis)
        known-ns (set (keys ns-index))]
    (->> ns-usages
         (keep (fn [{:keys [from to]}]
                 (when (and (contains? known-ns from)
                            (contains? known-ns to)
                            (not= from to))
                   {:from (get ns-index from)
                    :to (get ns-index to)
                    :kind :ns-require})))
         (into #{})
         (vec))))

;; -----------------------------------------------------------------------------
;; Main build function

(defn build-model
  "Build the complete model from analysis data.

   Returns a map containing:
   - :nodes - {id -> node} for all folders, namespaces, vars, and optionally type nodes
   - :edges - vector of {:from :to} edges (var-level and ns-level combined)

   Options:
   - :type-nodes-fn - optional function (fn [ns-index] -> nodes-map) to build
                      language-specific type nodes (e.g., schemas). The ns-index
                      is a map of {ns-sym -> node-id}.

   Edge aggregation (folder-level) and schema flow edges are computed
   on-demand by the view layer, not pre-computed here.

   Note: Single-child folder chains are pruned - the root of the tree is the
   first folder with multiple children or non-folder children."
  {:malli/schema [:=> [:cat :AnalysisData [:? :map]] :Model]}
  ([analysis] (build-model analysis {}))
  ([analysis {:keys [type-nodes-fn] :or {type-nodes-fn (constantly {})}}]
   (let [ns-defs (:namespace-definitions analysis)
         var-defs (:var-definitions analysis)

         ;; Build all node types with indexes
         {:keys [nodes index]} (build-folder-nodes ns-defs)
         folder-nodes nodes
         folder-index index

         {:keys [nodes index]} (build-namespace-nodes ns-defs folder-index)
         ns-nodes nodes
         ns-index index

         {:keys [nodes index]} (build-var-nodes var-defs ns-index)
         var-nodes nodes
         var-index index

         ;; Merge nodes
         merged-nodes (merge folder-nodes ns-nodes var-nodes)

         ;; Remove empty folders
         cleaned-nodes (core/remove-empty-folders merged-nodes)

         ;; Wire up parent-child relationships
         all-nodes (core/wire-children cleaned-nodes)

         ;; Find smart starting point and prune ancestors
         smart-root (core/find-smart-root all-nodes)
         pruned-nodes (core/prune-to-smart-root all-nodes smart-root)

         ;; Build raw var-level edges
         var-edges (build-edges analysis var-index ns-index)

         ;; Build namespace-level edges from require relationships
         ns-edges (build-ns-edges analysis ns-index)

         ;; Combine all edges (var-level + ns-level, deduplicated)
         all-edges (vec (into (set var-edges) ns-edges))

         ;; Build type nodes (e.g., schema nodes) using ns-index
         type-nodes (type-nodes-fn ns-index)

         ;; Merge type nodes into final nodes map and re-wire children
         ;; (type nodes have parent set but parent's children set needs updating)
         final-nodes (-> (merge pruned-nodes type-nodes)
                         core/wire-children)]

     {:nodes final-nodes
      :edges all-edges})))
