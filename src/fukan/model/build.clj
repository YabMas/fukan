(ns fukan.model.build
  "Language-agnostic model construction pipeline.
   Transforms normalized analysis data into the graph model: a tree of
   container, function, and schema nodes connected by directed dependency
   edges. Handles folder/namespace/var node construction, parent-child
   wiring, smart-root pruning, edge building, and contract attachment."
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
   [:namespace-definitions [:vector :NsDef]]
   [:var-definitions [:vector :VarDef]]
   [:var-usages [:vector :VarUsage]]
   [:namespace-usages {:optional true} [:vector :NsUsage]]])

;; -----------------------------------------------------------------------------
;; Model Schemas

(def ^:schema NodeId
  [:string {:description "Unique string identifier for a node in the model graph."}])

(def ^:schema NodeKind
  [:enum {:description "Structural kind: container (directory/namespace), function (var), or schema definition."}
   :container :function :schema])

(def ^:schema ContractFn
  [:map {:description "A public function entry in a module contract."}
   [:name :symbol]
   [:schema {:optional true, :description "Malli function schema [:=> [:cat inputs...] output]."} :any]
   [:doc {:optional true} :string]])

(def ^:schema Contract
  [:map {:description "A module's external boundary: the functions that callers outside the module use."}
   [:description {:optional true} :string]
   [:functions [:vector :ContractFn]]])

(def ^:schema Field
  [:map {:description "A named, typed property from a specification entity."}
   [:name :string]
   [:type-ref :string]
   [:description {:optional true} :string]])

(def ^:schema Surface
  [:map {:description "A module's public boundary contract derived from specification."}
   [:facing {:optional true} :string]
   [:description {:optional true} :string]
   [:exposes {:optional true} [:vector :Field]]
   [:guarantees {:optional true} [:vector :string]]
   [:provides {:optional true} [:vector :Field]]])

(def ^:schema NodeData
  [:or {:description "Kind-specific properties attached to a node, discriminated by :kind."}
   ;; Container data (directory or namespace)
   [:map {:description "Container node data: documentation, contract, and optional spec data."}
    [:kind [:= :container]]
    [:doc {:optional true} [:maybe :string]]
    [:contract {:optional true} :Contract]
    [:surface {:optional true} :Surface]
    [:fields {:optional true} [:vector :Field]]
    [:spec {:optional true, :description "Raw parsed specification data."} :any]]
   ;; Function data (var definition)
   [:map {:description "Function node data: documentation, visibility, and optional type signature."}
    [:kind [:= :function]]
    [:doc {:optional true} [:maybe :string]]
    [:private? {:optional true} :boolean]
    [:signature {:optional true, :description "Malli function schema [:=> [:cat inputs...] output]."} :any]]
   ;; Schema data (schema definition)
   [:map {:description "Schema node data: the Malli schema form and its keyword key."}
    [:kind [:= :schema]]
    [:schema-key :keyword]
    [:schema {:description "Malli schema form (arbitrary Malli syntax tree)."} :any]
    [:doc {:optional true} [:maybe :string]]]])

(def ^:schema Node
  [:map {:description "An entity in the system model: container, function, or schema definition."}
   [:id :NodeId]
   [:kind :NodeKind]
   [:label :string]
   [:description {:optional true} :string]
   [:parent {:optional true} [:maybe :NodeId]]
   [:children [:set :NodeId]]
   [:data {:optional true} :NodeData]])

(def ^:schema Edge
  [:map {:description "A directed dependency between two nodes."}
   [:from {:description "Node ID of the caller/referencer"} :NodeId]
   [:to {:description "Node ID of the callee/referenced entity"} :NodeId]])

(def ^:schema Model
  [:map {:description "The complete graph model of a codebase: all entity nodes and their directed dependency edges."}
   [:nodes [:map-of :NodeId :Node]]
   [:edges [:vector :Edge]]])

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

(defn- remove-empty-containers
  "Remove container nodes that have no children.
   Returns updated nodes map."
  [nodes]
  (let [;; First, wire children to see which containers are empty
        wired (wire-children nodes)
        ;; Find containers with no children
        empty-ids (->> wired
                       (filter (fn [[_id node]]
                                 (and (= :container (:kind node))
                                      (empty? (:children node)))))
                       (map first)
                       set)]
    ;; Remove empty containers
    (apply dissoc nodes empty-ids)))

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
  "Extract all unique directory paths from file paths."
  [filepaths]
  (->> filepaths
       (mapcat parent-dirs)
       (into #{})))

(defn- dir-parent
  "Get the parent directory path, or nil for top-level."
  [dir-path]
  (let [idx (str/last-index-of dir-path "/")]
    (when idx
      (subs dir-path 0 idx))))

(defn- build-folder-nodes-from-files
  "Build folder nodes from a list of file paths.
   Returns {:nodes {id -> node}, :index {path -> id}}."
  [filepaths]
  (let [all-dirs (extract-all-dirs filepaths)]
    (reduce (fn [acc dir-path]
              (let [id dir-path
                    label (str/join "." (rest (str/split dir-path #"/")))
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

(defn build-folder-nodes
  "Build folder nodes from namespace definitions.
   Returns {:nodes {id -> node}, :index {path -> id}}."
  [ns-defs]
  (build-folder-nodes-from-files (mapv :filename ns-defs)))

;; -----------------------------------------------------------------------------
;; Namespace node construction

(defn file-to-folder
  "Get the folder path for a file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (when (> (count parts) 1)
      (str/join "/" (butlast parts)))))

(defn build-namespace-nodes
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

(defn build-var-nodes
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

(defn build-edges
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

(defn build-ns-edges
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

(defn- remove-schema-defining-vars
  "Remove function nodes whose vars define schemas.
   These are redundant — the schema node represents them."
  [nodes]
  (let [schema-var-ids (->> (vals nodes)
                            (filter #(= :schema (:kind %)))
                            (map (fn [sn] (str (:parent sn) "/" (:label sn))))
                            set)
        cleaned (apply dissoc nodes schema-var-ids)]
    ;; Also remove from parent children sets
    (reduce (fn [acc var-id]
              (let [parent-id (:parent (get nodes var-id))]
                (if (and parent-id (contains? acc parent-id))
                  (update-in acc [parent-id :children] disj var-id)
                  acc)))
            cleaned
            schema-var-ids)))

;; -----------------------------------------------------------------------------
;; Contribution

(def ^:schema Contribution
  [:map {:description "A language contribution: pre-built nodes and edges ready for the build pipeline. Each language analyzer produces a contribution; contributions are merged before calling build-model."}
   [:source-files {:description "File paths for folder hierarchy construction."} [:vector :string]]
   [:nodes {:description "Pre-built nodes. Container nodes should have :parent nil and :filename in :data for folder parenting."} [:map-of :NodeId :Node]]
   [:edges {:description "Pre-built edges between nodes."} [:vector :Edge]]])

(defn- merge-node-pair
  "Merge two nodes with the same ID. Deep-merges :data maps for containers
   so that spec data (surface, fields, description) enriches impl data
   (doc, contract) rather than overwriting it. Non-container nodes or
   nodes with different kinds use simple last-wins merge."
  [a b]
  (if (and (= :container (:kind a)) (= :container (:kind b)))
    (-> (merge a b)
        (assoc :data (merge (:data a) (:data b)))
        (update :children (fn [c] (into (or (:children a) #{}) (or c #{})))))
    b))

(defn- merge-node-maps
  "Merge multiple node maps with deep merge for shared container IDs."
  [& nmaps]
  (reduce (fn [acc nmap]
            (reduce-kv (fn [m id node]
                         (if-let [existing (get m id)]
                           (assoc m id (merge-node-pair existing node))
                           (assoc m id node)))
                       acc nmap))
          {} nmaps))

(defn merge-contributions
  "Merge multiple language contributions into one.
   Container nodes with the same ID are deep-merged (spec enriches impl).
   Other nodes use last-wins. Edges are deduplicated."
  {:malli/schema [:=> [:cat [:* :Contribution]] :Contribution]}
  [& contributions]
  {:source-files (vec (mapcat :source-files contributions))
   :nodes (apply merge-node-maps (map :nodes contributions))
   :edges (vec (into #{} (mapcat :edges contributions)))})

;; -----------------------------------------------------------------------------
;; Main build function

(defn build-model
  "Build the complete model from a language contribution.

   A contribution contains pre-built nodes and edges from language analyzers.
   This function handles the language-agnostic pipeline:
   1. Build folder hierarchy from :source-files
   2. Parent container nodes under their folders
   3. Remove empty containers, wire children, smart-root prune
   4. Apply post-processing hooks (type nodes, signatures, contracts)

   Options:
   - :type-nodes-fn - optional function (fn [ns-index] -> nodes-map) to build
                      language-specific type nodes (e.g., schemas). The ns-index
                      is a map of {ns-sym -> node-id} derived from container nodes."
  {:malli/schema [:=> [:cat :Contribution [:? :map]] :Model]}
  ([contribution] (build-model contribution {}))
  ([contribution {:keys [type-nodes-fn] :or {type-nodes-fn (constantly {})}}]
   (let [source-files (:source-files contribution)
         contrib-nodes (:nodes contribution)
         contrib-edges (:edges contribution)

         ;; Build folder hierarchy from source files
         {:keys [nodes index]} (build-folder-nodes-from-files source-files)
         folder-nodes nodes
         folder-index index
         folder-ids (set (keys folder-nodes))

         ;; Set parents on contribution's container nodes from :filename in data
         parented-nodes (reduce-kv
                          (fn [acc id node]
                            (if (and (= :container (:kind node))
                                     (nil? (:parent node)))
                              (let [filename (get-in node [:data :filename])
                                    folder-path (when filename (file-to-folder filename))
                                    parent-id (when folder-path (get folder-index folder-path))]
                                (assoc acc id (assoc node :parent parent-id)))
                              (assoc acc id node)))
                          {} contrib-nodes)

         ;; Merge folder nodes + contribution nodes
         merged-nodes (merge folder-nodes parented-nodes)

         ;; Remove empty containers
         cleaned-nodes (remove-empty-containers merged-nodes)

         ;; Wire up parent-child relationships
         all-nodes (wire-children cleaned-nodes)

         ;; Find smart starting point and prune ancestors
         smart-root (find-smart-root all-nodes folder-ids)
         pruned-nodes (prune-to-smart-root all-nodes smart-root)

         ;; Derive ns-index from non-folder container nodes for hooks
         ns-index (->> (vals pruned-nodes)
                       (filter #(and (= :container (:kind %))
                                     (not (contains? folder-ids (:id %)))))
                       (reduce (fn [acc node]
                                 (assoc acc (symbol (:id node)) (:id node)))
                               {}))

         ;; Filter edges to surviving nodes, deduplicate
         node-ids (set (keys pruned-nodes))
         all-edges (->> contrib-edges
                        (filter (fn [{:keys [from to]}]
                                  (and (contains? node-ids from)
                                       (contains? node-ids to)
                                       (not= from to))))
                        (into #{})
                        vec)

         ;; Build type nodes (e.g., schema nodes) using ns-index
         type-nodes (type-nodes-fn ns-index)

         ;; Merge type nodes into final nodes map and re-wire children
         merged-with-types (-> (merge pruned-nodes type-nodes)
                               wire-children)

         ;; Remove var nodes that define schemas — they're represented by schema nodes
         final-nodes (remove-schema-defining-vars merged-with-types)

         ;; Attach function signatures from runtime metadata
         signed-nodes (attach-var-signatures final-nodes)

         ;; Attach contracts to containers
         contracted-nodes (attach-contracts signed-nodes)]

     {:nodes contracted-nodes
      :edges all-edges})))
