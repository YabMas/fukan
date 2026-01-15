(ns fukan.model
  "Internal graph model construction from analysis data.
   Builds a hierarchical structure of folders, namespaces, and vars
   with var-level dependency edges. Edge aggregation (ns/folder level)
   is computed on-demand by the view layer."
  (:require [clojure.string :as str]
            [fukan.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema NodeId :string)
(def ^:schema NodeKind [:enum :folder :namespace :var :schema])

(def ^:schema Node
  "A node in the graph. Kind-specific fields stored in :data map."
  [:map
   [:id NodeId]
   [:kind NodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe NodeId]]
   [:children [:set :string]]
   [:data {:optional true} :map]])  ; kind-specific: path, ns-sym, var-sym, doc, private?, schema-key, etc.

(def ^:schema Edge
  "A directed edge between two nodes. All info derived from connected nodes."
  [:map
   [:from NodeId]
   [:to NodeId]])

(def ^:schema Model
  "Simplified model: just nodes and edges. No pre-computed aggregations or indexes."
  [:map
   [:nodes [:map-of NodeId Node]]
   [:edges [:vector Edge]]])

;; -----------------------------------------------------------------------------
;; ID construction helpers

(defn- folder-id
  "Create a folder node ID from a directory path."
  [path]
  (str "folder:" path))

(defn- ns-id
  "Create a namespace node ID from a namespace symbol."
  [ns-sym]
  (str "ns:" (name ns-sym)))

(defn- var-id
  "Create a var node ID from namespace and var name."
  [ns-sym var-name]
  (str "var:" (name ns-sym) "/" (name var-name)))

(defn- schema-id
  "Create a schema node ID from a qualified keyword."
  [schema-key]
  (str "schema:" (namespace schema-key) "/" (name schema-key)))

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
   Returns a map of {folder-id -> node}."
  [ns-defs]
  (let [all-dirs (extract-all-dirs ns-defs)]
    (->> all-dirs
         (map (fn [dir-path]
                (let [id (folder-id dir-path)
                      label (last (str/split dir-path #"/"))
                      parent-path (dir-parent dir-path)]
                  [id {:id id
                       :kind :folder
                       :label label
                       :parent (when parent-path (folder-id parent-path))
                       :children #{}
                       :data {:path dir-path}}])))
         (into {}))))

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
   Returns a map of {ns-id -> node}."
  [ns-defs]
  (->> ns-defs
       (map (fn [{:keys [name filename doc]}]
              (let [id (ns-id name)
                    folder-path (file-to-folder filename)]
                [id {:id id
                     :kind :namespace
                     :label (str name)
                     :parent (when folder-path (folder-id folder-path))
                     :children #{}
                     :data {:ns-sym name
                            :filename filename
                            :doc doc}}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Var node construction

(defn- build-var-nodes
  "Build var nodes from var definitions (both public and private).
   Returns a map of {var-id -> node}."
  [var-defs]
  (->> var-defs
       (map (fn [{:keys [ns name filename row doc private]}]
              (let [id (var-id ns name)]
                [id {:id id
                     :kind :var
                     :label (str name)
                     :parent (ns-id ns)
                     :children #{}
                     :data {:var-sym name
                            :ns-sym ns
                            :filename filename
                            :row row
                            :doc doc
                            :private? (boolean private)}}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Wire up parent-child relationships

(defn- add-child-to-parent
  "Add a child ID to a parent node's children set."
  [nodes child-id parent-id]
  (if (and parent-id (contains? nodes parent-id))
    (update-in nodes [parent-id :children] conj child-id)
    nodes))

(defn- wire-children
  "Wire up parent-child relationships based on :parent fields."
  [nodes]
  (reduce (fn [acc [id node]]
            (if-let [parent-id (:parent node)]
              (add-child-to-parent acc id parent-id)
              acc))
          nodes
          nodes))

(defn- remove-empty-folders
  "Remove folder nodes that have no children.
   Returns updated nodes map."
  [nodes]
  (let [;; First, wire children to see which folders are empty
        wired (wire-children nodes)
        ;; Find folders with no children
        empty-folder-ids (->> wired
                              (filter (fn [[_ node]]
                                        (and (= :folder (:kind node))
                                             (empty? (:children node)))))
                              (map first)
                              set)]
    ;; Remove empty folders
    (apply dissoc nodes empty-folder-ids)))

;; -----------------------------------------------------------------------------
;; Edge construction (raw edges)

(defn- build-var-index
  "Index all var definitions by [ns name] -> var-id.
   Includes both public and private vars."
  [var-defs]
  (->> var-defs
       (map (fn [{:keys [ns name]}]
              [[ns name] (var-id ns name)]))
       (into {})))

(defn- build-edges
  "Build raw edges from actual call relationships.

   Creates edges for each var usage where the target var is defined in the codebase.
   - When from-var is present: creates var-to-var edge
   - When from-var is nil (top-level or anonymous): creates ns-to-var edge

   Returns a vector of {:from node-id, :to var-id} edges."
  [analysis]
  (let [var-defs (:var-definitions analysis)
        var-usages (:var-usages analysis)
        var-index (build-var-index var-defs)
        ;; Set of namespaces that have nodes (to filter external deps)
        known-ns (into #{} (map :ns var-defs))]
    (->> var-usages
         (keep (fn [{:keys [from from-var to name]}]
                 (when-let [to-id (get var-index [to name])]
                   (let [from-id (if from-var
                                   ;; Normal case: var-to-var edge
                                   (get var-index [from from-var])
                                   ;; Top-level/anonymous: ns-to-var edge
                                   (when (contains? known-ns from)
                                     (ns-id from)))]
                     (when (and from-id (not= from-id to-id))
                       {:from from-id :to to-id})))))
         (into #{})
         (vec))))

(defn- build-ns-edges
  "Build namespace-to-namespace edges from require relationships.
   Creates edges for each namespace require where both namespaces
   are defined in the codebase.

   Returns a vector of {:from ns-id, :to ns-id, :kind :ns-require} edges.
   The :kind field distinguishes these from var-level edges for filtering."
  [analysis]
  (let [ns-defs (:namespace-definitions analysis)
        ns-usages (:namespace-usages analysis)
        known-ns (into #{} (map :name ns-defs))]
    (->> ns-usages
         (keep (fn [{:keys [from to]}]
                 (when (and (contains? known-ns from)
                            (contains? known-ns to)
                            (not= from to))
                   {:from (ns-id from) :to (ns-id to) :kind :ns-require})))
         (into #{})
         (vec))))

;; -----------------------------------------------------------------------------
;; Smart root detection

(defn- find-smart-root
  "Find a smart starting container by skipping single-child folders.
   Returns the ID of the deepest folder that has multiple children
   or non-folder children."
  [nodes]
  (loop [container-id nil]
    (let [children (->> (vals nodes)
                        (filter (fn [node]
                                  (if container-id
                                    (= (:parent node) container-id)
                                    ;; Root level: no parent or parent not in nodes
                                    (let [p (:parent node)]
                                      (or (nil? p) (not (contains? nodes p)))))))
                        (remove #(= (:kind %) :var)))] ; Folders and namespaces only
      ;; If exactly one child and it's a folder, descend
      (if (and (= 1 (count children))
               (= :folder (:kind (first children))))
        (recur (:id (first children)))
        container-id))))

(defn- prune-to-smart-root
  "Remove nodes above smart-root and set smart-root's parent to nil.
   Returns the pruned nodes map."
  [nodes smart-root-id]
  (if (nil? smart-root-id)
    ;; No smart-root means we're already at the real root
    nodes
    ;; Find all ancestors to remove
    (let [ancestors-to-remove (loop [current-id smart-root-id
                                     ancestors #{}]
                                (let [node (get nodes current-id)
                                      parent-id (:parent node)]
                                  (if (or (nil? parent-id) (not (contains? nodes parent-id)))
                                    ancestors
                                    (recur parent-id (conj ancestors parent-id)))))]
      (-> nodes
          ;; Remove ancestor nodes
          (#(apply dissoc % ancestors-to-remove))
          ;; Set smart-root's parent to nil
          (assoc-in [smart-root-id :parent] nil)))))

;; -----------------------------------------------------------------------------
;; Schema flow construction

(defn- build-schema-nodes
  "Build schema nodes from all registered schemas.
   Schema nodes are placed inside their owning namespace container.
   Returns a map of {schema-node-id -> node}."
  []
  (->> (schema/all-schemas)
       (map (fn [k]
              (let [id (schema-id k)
                    owner-ns (namespace k)
                    parent-ns-id (ns-id (symbol owner-ns))]
                [id {:id id
                     :kind :schema
                     :label (name k)
                     :parent parent-ns-id  ; schemas belong to their owning namespace
                     :children #{}
                     :data {:schema-key k
                            :owner-ns owner-ns}}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Index building

(defn build-model
  "Build the complete model from clj-kondo analysis.

   Returns a simplified map containing:
   - :nodes - {id -> node} for all folders, namespaces, vars, and schemas
   - :edges - vector of {:from :to} edges (var-level and ns-level combined)

   Edge aggregation (folder-level) and schema flow edges are computed
   on-demand by the view layer, not pre-computed here.

   Note: Single-child folder chains are pruned - the root of the tree is the
   first folder with multiple children or non-folder children."
  {:malli/schema [:=> [:cat :fukan.analysis/AnalysisData] :fukan.model/Model]}
  [analysis]
  (let [ns-defs (:namespace-definitions analysis)
        var-defs (:var-definitions analysis)

        ;; Build all node types
        folder-nodes (build-folder-nodes ns-defs)
        ns-nodes (build-namespace-nodes ns-defs)
        var-nodes (build-var-nodes var-defs)

        ;; Merge nodes
        merged-nodes (merge folder-nodes ns-nodes var-nodes)

        ;; Remove empty folders
        cleaned-nodes (remove-empty-folders merged-nodes)

        ;; Wire up parent-child relationships
        all-nodes (wire-children cleaned-nodes)

        ;; Find smart starting point and prune ancestors
        smart-root (find-smart-root all-nodes)
        pruned-nodes (prune-to-smart-root all-nodes smart-root)

        ;; Build raw var-level edges
        var-edges (build-edges analysis)

        ;; Build namespace-level edges from require relationships
        ns-edges (build-ns-edges analysis)

        ;; Combine all edges (var-level + ns-level, deduplicated)
        all-edges (vec (into (set var-edges) ns-edges))

        ;; Build schema nodes (only those with registered schemas)
        ;; Schema flow is computed on-demand, but we include schema nodes
        ;; so they can be referenced in the graph
        all-schema-nodes (build-schema-nodes)
        ;; For now, include all schema nodes - filtering can happen in views
        ;; based on whether they're actually used in the current view

        ;; Merge schema nodes into final nodes map
        final-nodes (merge pruned-nodes all-schema-nodes)]

    {:nodes final-nodes
     :edges all-edges}))
