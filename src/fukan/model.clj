(ns fukan.model
  "Internal graph model construction from analysis data.
   Builds a hierarchical structure of folders, namespaces, and vars
   with dependency edges and indexes for efficient querying."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [fukan.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:private NodeId :string)
(def ^:private NodeKind [:enum :folder :namespace :var :schema])

(def ^:private Node
  [:map
   [:id NodeId]
   [:kind NodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe NodeId]]
   [:children :set]
   ;; Optional fields depending on kind
   [:path {:optional true} :string]           ; folder
   [:ns-sym {:optional true} :symbol]         ; namespace, var
   [:var-sym {:optional true} :symbol]        ; var
   [:filename {:optional true} :string]       ; namespace, var
   [:row {:optional true} :int]               ; var
   [:doc {:optional true} [:maybe :string]]   ; namespace, var
   [:private? {:optional true} :boolean]      ; var
   [:schema-key {:optional true} :keyword]    ; schema
   [:owner-ns {:optional true} :string]])     ; schema

(def ^:private Edge
  [:map
   [:from NodeId]
   [:to NodeId]])

(def ^:private SchemaFlowEdge
  [:map
   [:from NodeId]
   [:to NodeId]
   [:schema-key :keyword]
   [:edge-type [:enum :schema-flow]]])

(def ^:private EdgeIndex
  [:map-of NodeId [:vector Edge]])

(def ^:private Model
  [:map
   [:nodes [:map-of NodeId Node]]
   [:edges [:vector Edge]]
   [:ns-edges [:vector Edge]]
   [:folder-edges [:vector Edge]]
   [:schema-edges [:vector SchemaFlowEdge]]
   [:edges-by-from EdgeIndex]
   [:edges-by-to EdgeIndex]
   [:ns-edges-by-from EdgeIndex]
   [:ns-edges-by-to EdgeIndex]
   [:folder-edges-by-from EdgeIndex]
   [:folder-edges-by-to EdgeIndex]
   [:schema-edges-by-from EdgeIndex]
   [:schema-edges-by-to EdgeIndex]])

;; Register for sidebar display
(schema/register! :fukan.model/NodeId NodeId)
(schema/register! :fukan.model/NodeKind NodeKind)
(schema/register! :fukan.model/Node Node)
(schema/register! :fukan.model/Edge Edge)
(schema/register! :fukan.model/SchemaFlowEdge SchemaFlowEdge)
(schema/register! :fukan.model/EdgeIndex EdgeIndex)
(schema/register! :fukan.model/Model Model)

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
                       :path dir-path
                       :parent (when parent-path (folder-id parent-path))
                       :children #{}}])))
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
                     :ns-sym name
                     :filename filename
                     :doc doc
                     :parent (when folder-path (folder-id folder-path))
                     :children #{}}])))
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
                     :var-sym name
                     :ns-sym ns
                     :filename filename
                     :row row
                     :doc doc
                     :private? (boolean private)
                     :parent (ns-id ns)
                     :children #{}}])))
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
  "Build raw var-to-var edges from actual call relationships.

   Creates a direct edge for each var usage where both the calling var
   and the called var are defined in the codebase.

   Returns a vector of {:from var-id, :to var-id} edges."
  [analysis]
  (let [var-defs (:var-definitions analysis)
        var-usages (:var-usages analysis)
        var-index (build-var-index var-defs)]
    (->> var-usages
         (keep (fn [{:keys [from from-var to name]}]
                 (when-let [from-id (and from-var (get var-index [from from-var]))]
                   (when-let [to-id (get var-index [to name])]
                     (when (not= from-id to-id) ; no self-loops
                       {:from from-id :to to-id})))))
         (into #{})
         (vec))))

;; -----------------------------------------------------------------------------
;; Multi-level edge aggregation

(defn- build-ns-edges
  "Aggregate var edges into namespace-level edges.
   Creates edge ns-A -> ns-B if any var in ns-A depends on any var in ns-B.
   Excludes self-edges."
  [var-edges nodes]
  (->> var-edges
       (map (fn [{:keys [from to]}]
              (let [from-ns (:parent (get nodes from))
                    to-ns (:parent (get nodes to))]
                (when (and from-ns to-ns (not= from-ns to-ns))
                  {:from from-ns :to to-ns}))))
       (remove nil?)
       (into #{})
       vec))

(defn- build-folder-edges
  "Aggregate namespace edges into folder-level edges.
   Creates edge folder-A -> folder-B if any ns in folder-A depends on any ns in folder-B.
   Excludes self-edges."
  [ns-edges nodes]
  (->> ns-edges
       (map (fn [{:keys [from to]}]
              (let [from-folder (:parent (get nodes from))
                    to-folder (:parent (get nodes to))]
                (when (and from-folder to-folder (not= from-folder to-folder))
                  {:from from-folder :to to-folder}))))
       (remove nil?)
       (into #{})
       vec))

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
;; Schema flow analysis
;;
;; Functions to extract schema references for data flow analysis.

(defn- extract-schema-refs
  "Extract all qualified keyword schema references from a schema form.
   Returns a set of keywords (e.g., #{:fukan.model/Node :fukan.model/Edge}).
   Only returns refs that are registered in our schema registry."
  [schema-form]
  (let [registered-schemas (set (schema/all-schemas))
        refs (atom #{})]
    (letfn [(walk [s]
              (cond
                ;; Qualified keyword - potential schema reference
                (qualified-keyword? s)
                (when (contains? registered-schemas s)
                  (swap! refs conj s))

                ;; Vector form - recurse into children
                (vector? s)
                (doseq [child (rest s)] (walk child))

                ;; Map form - recurse into values
                (map? s)
                (doseq [v (vals s)] (walk v))))]
      (walk schema-form)
      @refs)))

(defn- extract-fn-schema-flow
  "Extract input and output schema references from a function schema.
   Expects schema in form [:=> [:cat input1 input2 ...] output].
   Returns {:inputs #{schema-keys...} :outputs #{schema-keys...}}
   or nil if not a function schema."
  [fn-schema]
  (when (and (vector? fn-schema) (= :=> (first fn-schema)))
    (let [[_ input output] fn-schema
          in-schemas (if (and (vector? input) (= :cat (first input)))
                       (rest input)
                       [input])]
      {:inputs (into #{} (mapcat extract-schema-refs in-schemas))
       :outputs (extract-schema-refs output)})))

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
                     :schema-key k
                     :owner-ns owner-ns
                     :parent parent-ns-id  ; schemas belong to their owning namespace
                     :children #{}}])))
       (into {})))

(defn- collect-schema-producers-consumers
  "Scan loaded namespaces for vars with :malli/schema metadata.
   Extracts which namespaces produce (output) and consume (input) each schema.

   Returns {:producers {schema-key -> #{ns-id...}}
            :consumers {schema-key -> #{ns-id...}}}"
  [ns-nodes]
  (let [producers (atom {})
        consumers (atom {})]
    ;; Only scan namespaces that are in our model
    (doseq [[ns-node-id node] ns-nodes
            :when (= :namespace (:kind node))
            :let [ns-sym (:ns-sym node)]]
      (when-let [ns-obj (find-ns ns-sym)]
        (doseq [[var-sym v] (ns-publics ns-obj)]
          (when-let [fn-schema (:malli/schema (meta v))]
            (when-let [{:keys [inputs outputs]} (extract-fn-schema-flow fn-schema)]
              ;; This namespace produces the output schemas
              (doseq [out-schema outputs]
                (swap! producers update out-schema (fnil conj #{}) ns-node-id))
              ;; This namespace consumes the input schemas
              (doseq [in-schema inputs]
                (swap! consumers update in-schema (fnil conj #{}) ns-node-id)))))))
    {:producers @producers
     :consumers @consumers}))

(defn- find-data-flow-path
  "Find the path data takes from producer-ns to consumer-ns using the call graph.

   Data flows: if A calls B (A → B in ns-edges), and B produces data, A receives it.
   Data passes: if A then calls C (A → C in ns-edges), A can pass the data to C.

   Algorithm:
   1. Find who calls producer (they receive the data via return value)
   2. Then follow forward edges (who they call) to trace data passing
   3. Continue until we reach consumer

   Uses BFS to find shortest path. Returns the path as a vector of ns-ids,
   or nil if no path exists."
  [producer-ns consumer-ns callers-index ns-edges-by-from]
  (if (= producer-ns consumer-ns)
    [producer-ns] ; Producer and consumer are same namespace
    ;; Step 1: Find who receives data from producer (callers of producer)
    (let [initial-receivers (get callers-index producer-ns #{})]
      (when (seq initial-receivers)
        ;; Check if consumer directly calls producer
        (if (contains? initial-receivers consumer-ns)
          [producer-ns consumer-ns]
          ;; BFS from receivers, following forward edges (who they call)
          (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                             (for [r initial-receivers]
                               [r [producer-ns r]]))
                 visited (into #{producer-ns} initial-receivers)]
            (when-let [[current path] (peek queue)]
              (if (= current consumer-ns)
                path ;; Found the consumer
                ;; Follow forward edges: who does current call?
                (let [callees (->> (get ns-edges-by-from current [])
                                   (map :to)
                                   (remove visited))
                      new-paths (for [callee callees]
                                  [callee (conj path callee)])]
                  (recur (into (pop queue) new-paths)
                         (into visited callees)))))))))))

(defn- build-schema-flow-edges
  "Build schema data flow edges that trace the actual path through the call graph.

   Instead of direct producer -> schema -> consumer edges, this traces how data
   actually flows: producer -> intermediary1 -> intermediary2 -> ... -> consumer

   For terminal outputs (schemas produced but not consumed elsewhere), creates
   an edge from the producer namespace to the schema node itself.

   Returns vector of {:from :to :schema-key :edge-type}"
  [producers-consumers ns-edges]
  (let [{:keys [producers consumers]} producers-consumers
        all-schemas (into #{} (concat (keys producers) (keys consumers)))
        ;; Build callers index: who calls each namespace?
        ;; If A → B is in ns-edges (A calls B), then A is a caller of B
        callers-index (reduce (fn [acc {:keys [from to]}]
                                (update acc to (fnil conj #{}) from))
                              {}
                              ns-edges)
        ns-edges-by-from (group-by :from ns-edges)

        ;; Cross-namespace flow edges (existing logic)
        flow-edges
        (->> all-schemas
             (mapcat (fn [schema-key]
                       (let [producer-nses (get producers schema-key #{})
                             consumer-nses (get consumers schema-key #{})]
                         ;; Path-based flow edges: trace from each producer to each consumer
                         (for [producer-ns producer-nses
                               consumer-ns consumer-nses
                               :when (not= producer-ns consumer-ns)
                               :let [path (find-data-flow-path producer-ns consumer-ns
                                                               callers-index ns-edges-by-from)]
                               :when (and path (> (count path) 1))
                               ;; Create edges for each step in the path
                               [from-ns to-ns] (partition 2 1 path)]
                           {:from from-ns
                            :to to-ns
                            :schema-key schema-key
                            :edge-type :schema-flow})))))

        ;; Terminal output edges: producer-ns -> schema node
        ;; For schemas that are produced but have no consumers
        terminal-edges
        (->> all-schemas
             (mapcat (fn [schema-key]
                       (let [producer-nses (get producers schema-key #{})
                             consumer-nses (get consumers schema-key #{})]
                         ;; Terminal: has producers but no consumers (or only same-ns consumers)
                         (when (and (seq producer-nses)
                                    (empty? (remove producer-nses consumer-nses)))
                           (for [producer-ns producer-nses]
                             {:from producer-ns
                              :to (schema-id schema-key)
                              :schema-key schema-key
                              :edge-type :schema-flow}))))))]
    ;; Deduplicate and combine
    (->> (concat flow-edges terminal-edges)
         (into #{})
         (vec))))

(defn- filter-schema-nodes-with-flow
  "Filter schema nodes to only include those with actual data flow.
   Returns schemas that have at least one flow edge (based on schema-key)."
  [schema-nodes schema-edges]
  (let [schemas-with-flow (->> schema-edges
                               (map :schema-key)
                               (into #{}))
        schema-ids-with-flow (into #{} (map schema-id schemas-with-flow))]
    (select-keys schema-nodes schema-ids-with-flow)))

;; -----------------------------------------------------------------------------
;; Index building

(defn build-model
  "Build the complete model from clj-kondo analysis.

   Returns a map containing:
   - :nodes - {id -> node} for all folders, namespaces, vars, and schemas
   - :edges - vector of {:from :to} var-level edges
   - :ns-edges - vector of {:from :to} namespace-level edges (aggregated from var edges)
   - :folder-edges - vector of {:from :to} folder-level edges (aggregated from ns edges)
   - :schema-edges - vector of schema data flow edges (producer-ns -> schema -> consumer-ns)
   - :edges-by-from, :edges-by-to - var edge indexes
   - :ns-edges-by-from, :ns-edges-by-to - namespace edge indexes
   - :folder-edges-by-from, :folder-edges-by-to - folder edge indexes
   - :schema-edges-by-from, :schema-edges-by-to - schema flow edge indexes

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

        ;; Merge and wire up relationships
        all-nodes (-> (merge folder-nodes ns-nodes var-nodes)
                      wire-children)

        ;; Find smart starting point and prune ancestors
        smart-root (find-smart-root all-nodes)
        pruned-nodes (prune-to-smart-root all-nodes smart-root)

        ;; Build raw var-level edges
        var-edges (build-edges analysis)

        ;; Build aggregated namespace-level edges
        ns-edges (build-ns-edges var-edges pruned-nodes)

        ;; Build aggregated folder-level edges
        folder-edges (build-folder-edges ns-edges pruned-nodes)

        ;; Build schema data flow
        all-schema-nodes (build-schema-nodes)
        producers-consumers (collect-schema-producers-consumers pruned-nodes)
        schema-edges (build-schema-flow-edges producers-consumers ns-edges)
        ;; Only include schemas that have actual flow edges
        schema-nodes (filter-schema-nodes-with-flow all-schema-nodes schema-edges)

        ;; Merge schema nodes into final nodes map
        final-nodes (merge pruned-nodes schema-nodes)]

    {:nodes final-nodes
     :edges var-edges
     :ns-edges ns-edges
     :folder-edges folder-edges
     :schema-edges schema-edges
     :edges-by-from (group-by :from var-edges)
     :edges-by-to (group-by :to var-edges)
     :ns-edges-by-from (group-by :from ns-edges)
     :ns-edges-by-to (group-by :to ns-edges)
     :folder-edges-by-from (group-by :from folder-edges)
     :folder-edges-by-to (group-by :to folder-edges)
     :schema-edges-by-from (group-by :from schema-edges)
     :schema-edges-by-to (group-by :to schema-edges)}))
