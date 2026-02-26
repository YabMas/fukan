(ns fukan.model.build
  "Language-agnostic model construction pipeline.
   Builds the internal graph model from analysis data."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Analysis Schemas
;;
;; These schemas define the normalized format that any language analyzer
;; should produce. Language-specific analyzers convert their native format
;; to these generic structures.

(def ^:schema NsDef
  [:map {:description "A namespace/module definition."}
   [:name :symbol]
   [:filename :string]
   [:doc {:optional true} [:maybe :string]]])

(def ^:schema VarDef
  [:map {:description "A variable/function/symbol definition."}
   [:ns :symbol]
   [:name :symbol]
   [:filename :string]
   [:row :int]
   [:doc {:optional true} [:maybe :string]]
   [:private {:optional true} :boolean]])

(def ^:schema VarUsage
  [:map {:description "A reference from one var to another."}
   [:from {:description "Namespace containing the call site"} :symbol]
   [:from-var {:optional true :description "Var at the call site, nil if top-level"} :symbol]
   [:to {:description "Namespace containing the target var"} :symbol]
   [:name {:description "Name of the referenced var"} :symbol]])

(def ^:schema NsUsage
  [:map {:description "A namespace require/import relationship."}
   [:from :symbol]
   [:to :symbol]
   [:filename :string]])

(def ^:schema AnalysisData
  [:map {:description "The normalized output format from any language analyzer."}
   [:namespace-definitions [:vector NsDef]]
   [:var-definitions [:vector VarDef]]
   [:var-usages [:vector VarUsage]]
   [:namespace-usages {:optional true} [:vector NsUsage]]])

;; -----------------------------------------------------------------------------
;; Model Schemas

(def ^:schema NodeId
  [:string {:description "Unique string identifier for a node in the model graph."}])

(def ^:schema NodeKind
  [:enum {:description "Structural kind: container (directory/namespace), function (var), or schema definition."}
   :container :function :schema])

(def ^:schema Node
  [:map {:description "An entity in the codebase graph: directory, namespace, function, or schema definition."}
   [:id NodeId]
   [:kind NodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe NodeId]]
   [:children [:set :string]]
   [:data {:optional true :description "Kind-specific properties: :doc, :private?, :schema-key, :schema, :contract, :signature"} :map]])

(def ^:schema Edge
  [:map {:description "A directed dependency between two nodes."}
   [:from {:description "Node ID of the caller/referencer"} NodeId]
   [:to {:description "Node ID of the callee/referenced entity"} NodeId]])

(def ^:schema Model
  [:map {:description "The complete graph model of a codebase: all entity nodes and their directed dependency edges."}
   [:nodes [:map-of NodeId Node]]
   [:edges [:vector Edge]]])

;; -----------------------------------------------------------------------------
;; Tree Operations

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
   folder-ids is the set of node IDs that were created as directory nodes.
   Returns updated nodes map."
  [nodes folder-ids]
  (let [;; First, wire children to see which folders are empty
        wired (wire-children nodes)
        ;; Find folders with no children
        empty-folder-ids (->> wired
                              (filter (fn [[id node]]
                                        (and (contains? folder-ids id)
                                             (empty? (:children node)))))
                              (map first)
                              set)]
    ;; Remove empty folders
    (apply dissoc nodes empty-folder-ids)))

(defn- find-smart-root
  "Find a smart starting container by skipping single-child folder containers.
   folder-ids is the set of node IDs that were created as directory nodes.
   Returns the ID of the deepest folder that has multiple children
   or non-folder children."
  [nodes folder-ids]
  (loop [container-id nil]
    (let [children (->> (vals nodes)
                        (filter (fn [node]
                                  (if container-id
                                    (= (:parent node) container-id)
                                    ;; Root level: no parent or parent not in nodes
                                    (let [p (:parent node)]
                                      (or (nil? p) (not (contains? nodes p)))))))
                        (remove #(= (:kind %) :function)))] ; Containers only
      ;; If exactly one child and it's a folder, descend
      (if (and (= 1 (count children))
               (contains? folder-ids (:id (first children))))
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
              (let [id dir-path
                    label (last (str/split dir-path #"/"))
                    parent-path (dir-parent dir-path)
                    parent-id (get-in acc [:index parent-path])
                    node {:id id
                          :kind :container
                          :label label
                          :parent parent-id
                          :children #{}
                          :data {:kind :container}}]
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
            (let [id (str name)
                  folder-path (file-to-folder filename)
                  parent-id (get folder-index folder-path)
                  node {:id id
                        :kind :container
                        :label (str name)
                        :parent parent-id
                        :children #{}
                        :data {:kind :container
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
  (reduce (fn [acc {:keys [ns name doc private]}]
            (let [id (str ns "/" name)
                  parent-id (get ns-index ns)
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
                  (assoc-in [:index [ns name]] id))))
          {:nodes {} :index {}}
          var-defs))

;; -----------------------------------------------------------------------------
;; Contract construction

(defn- resolve-fn-ref
  "Resolve a qualified symbol to {:name :schema}.
   Requires the namespace if not already loaded.
   Throws if the var is missing or has no :malli/schema metadata."
  [sym]
  (let [ns-sym (symbol (namespace sym))
        var-sym (symbol (name sym))]
    (try
      (require ns-sym)
      (catch Exception _ nil))
    (let [ns-obj (or (find-ns ns-sym)
                     (throw (ex-info (str "Contract references unknown namespace: " ns-sym)
                                     {:sym sym :ns ns-sym})))
          v      (or (ns-resolve ns-obj var-sym)
                     (throw (ex-info (str "Contract references unknown var: " sym)
                                     {:sym sym})))
          schema (or (:malli/schema (meta v))
                     (throw (ex-info (str "Contract function missing :malli/schema metadata: " sym)
                                     {:sym sym})))]
      (cond-> {:name (name var-sym)
               :schema schema}
        (:doc (meta v)) (assoc :doc (:doc (meta v))))))
)

(defn- read-contract-file
  "Read contract.edn from a directory path if present.
   Resolves qualified symbols to {:name :schema} format."
  [dir-path]
  (when (and dir-path (not= dir-path ""))
    (let [file (io/file dir-path "contract.edn")]
      (when (.exists file)
        (let [raw (edn/read-string (slurp file))]
          (update raw :functions
                  (fn [fns]
                    (mapv resolve-fn-ref fns))))))))

(defn- infer-namespace-contract
  "Infer a contract for a namespace from its child function nodes.
   Reads signatures from already-built nodes instead of going to runtime.
   Excludes schema-defining vars (they have corresponding schema nodes)."
  [nodes ns-id]
  (let [ns-sym (symbol ns-id)
        ns-obj (find-ns ns-sym)
        description (when ns-obj (-> ns-obj meta :doc))
        schema-var-ids (->> (vals nodes)
                            (filter #(and (= :schema (:kind %))
                                          (= ns-id (:parent %))))
                            (map (fn [sn] (str ns-id "/" (:label sn))))
                            set)
        functions (->> (vals nodes)
                       (filter #(and (= :function (:kind %))
                                     (= ns-id (:parent %))
                                     (not (get-in % [:data :private?]))
                                     (not (contains? schema-var-ids (:id %)))
                                     (get-in % [:data :signature])))
                       (mapv (fn [node]
                               (cond-> {:name (:label node)
                                        :schema (get-in node [:data :signature])}
                                 (get-in node [:data :doc])
                                 (assoc :doc (get-in node [:data :doc]))))))]
    (when (seq functions)
      {:description description
       :functions functions})))

(defn- attach-var-signatures
  "Attach :signature to function nodes that have :malli/schema metadata.
   Excludes vars that define schemas (they have a corresponding schema node)."
  [nodes]
  (let [schema-var-ids (->> (vals nodes)
                            (filter #(= :schema (:kind %)))
                            (map (fn [sn] (str (:parent sn) "/" (:label sn))))
                            set)]
    (reduce (fn [acc [id node]]
              (if (and (= :function (:kind node))
                       (not (contains? schema-var-ids id)))
                (let [[ns-str var-str] (str/split id #"/" 2)
                      ns-sym (symbol ns-str)
                      var-sym (symbol var-str)
                      schema (try (some-> (find-ns ns-sym)
                                          (ns-resolve var-sym)
                                          meta :malli/schema)
                                  (catch Exception _ nil))]
                  (if schema
                    (assoc acc id (assoc-in node [:data :signature] schema))
                    (assoc acc id node)))
                (assoc acc id node)))
            {} nodes)))

(defn- attach-contract
  "Attach a contract to a node's :data map when present."
  [node contract]
  (if contract
    (update node :data #(assoc (or % {}) :contract contract))
    node))

(defn- attach-contracts
  "Attach module and namespace contracts to container nodes.
   Folder contracts come from contract.edn (using node ID as path);
   namespace contracts are inferred from child function nodes.
   Returns updated nodes map."
  [nodes]
  (reduce (fn [acc [id node]]
            (if (= :container (:kind node))
              ;; Try folder contract first (contract.edn at node's path),
              ;; then fall back to inferred namespace contract
              (let [contract (or (read-contract-file id)
                                 (infer-namespace-contract nodes id))]
                (assoc acc id (attach-contract node contract)))
              (assoc acc id node)))
          {}
          nodes))

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
         folder-ids (set (keys folder-nodes))

         {:keys [nodes index]} (build-namespace-nodes ns-defs folder-index)
         ns-nodes nodes
         ns-index index

         {:keys [nodes index]} (build-var-nodes var-defs ns-index)
         var-nodes nodes
         var-index index

         ;; Merge nodes
         merged-nodes (merge folder-nodes ns-nodes var-nodes)

         ;; Remove empty folders
         cleaned-nodes (remove-empty-folders merged-nodes folder-ids)

         ;; Wire up parent-child relationships
         all-nodes (wire-children cleaned-nodes)

         ;; Find smart starting point and prune ancestors
         smart-root (find-smart-root all-nodes folder-ids)
         pruned-nodes (prune-to-smart-root all-nodes smart-root)

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
                         wire-children)

         ;; Attach function signatures from runtime metadata
         ;; (runs after schema nodes merged so schema-defining vars are excluded)
         signed-nodes (attach-var-signatures final-nodes)

         ;; Attach contracts to containers
         contracted-nodes (attach-contracts signed-nodes)]

     {:nodes contracted-nodes
      :edges all-edges})))
