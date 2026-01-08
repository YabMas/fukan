(ns fukan.model
  "Internal graph model construction from analysis data.
   Builds a hierarchical structure of folders, namespaces, and vars
   with dependency edges and indexes for efficient querying."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; ID construction helpers

(defn folder-id
  "Create a folder node ID from a directory path."
  [path]
  (str "folder:" path))

(defn ns-id
  "Create a namespace node ID from a namespace symbol."
  [ns-sym]
  (str "ns:" (name ns-sym)))

(defn var-id
  "Create a var node ID from namespace and var name."
  [ns-sym var-name]
  (str "var:" (name ns-sym) "/" (name var-name)))

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

(defn build-folder-nodes
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

(defn build-namespace-nodes
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

(defn build-var-nodes
  "Build var nodes from var definitions (public vars only).
   Returns a map of {var-id -> node}."
  [var-defs]
  (->> var-defs
       (remove :private)
       (map (fn [{:keys [ns name filename row doc]}]
              (let [id (var-id ns name)]
                [id {:id id
                     :kind :var
                     :label (str name)
                     :var-sym name
                     :ns-sym ns
                     :filename filename
                     :row row
                     :doc doc
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

(defn wire-children
  "Wire up parent-child relationships based on :parent fields."
  [nodes]
  (reduce (fn [acc [id node]]
            (if-let [parent-id (:parent node)]
              (add-child-to-parent acc id parent-id)
              acc))
          nodes
          nodes))

;; -----------------------------------------------------------------------------
;; Edge construction with private attribution

(defn- build-var-index
  "Index all var definitions by [ns name] -> {:private? bool, :id string}.
   Includes both public and private vars."
  [var-defs]
  (->> var-defs
       (map (fn [{:keys [ns name private]}]
              [[ns name] {:private? (boolean private)
                          :id (var-id ns name)}]))
       (into {})))

(defn- build-internal-call-graph
  "Build internal call graph: {ns -> {from-var -> #{to-var}}}.
   Only includes calls within the same namespace."
  [var-usages var-index]
  (->> var-usages
       (filter (fn [{:keys [from to from-var name]}]
                 (and from-var              ; has a calling var
                      (= from to)           ; same namespace
                      (contains? var-index [to name])))) ; target is defined
       (reduce (fn [acc {:keys [from from-var name]}]
                 (update-in acc [from from-var] (fnil conj #{}) name))
               {})))

(defn- build-external-call-graph
  "Build external call graph: {[ns var] -> #{[target-ns target-var]}}.
   Only includes calls to different namespaces."
  [var-usages var-index]
  (->> var-usages
       (filter (fn [{:keys [from to from-var name]}]
                 (and from-var              ; has a calling var
                      (not= from to)        ; different namespace
                      (contains? var-index [to name])))) ; target is defined
       (reduce (fn [acc {:keys [from from-var to name]}]
                 (update acc [from from-var] (fnil conj #{}) [to name]))
               {})))

(defn- responsibility-set
  "Compute the set of vars a public var is responsible for.
   Uses BFS through internal calls to find all reachable private vars.
   Returns a set of var names (symbols) within the namespace."
  [public-var internal-calls-for-ns]
  (loop [visited #{}
         queue [public-var]]
    (if (empty? queue)
      visited
      (let [current (first queue)
            callees (get internal-calls-for-ns current #{})
            new-callees (remove visited callees)]
        (recur (conj visited current)
               (into (vec (rest queue)) new-callees))))))

(defn- public-vars-by-ns
  "Group public var names by namespace.
   Returns {ns-sym -> #{var-name}}."
  [var-index]
  (->> var-index
       (remove (fn [[_ {:keys [private?]}]] private?))
       (map (fn [[[ns name] _]] [ns name]))
       (reduce (fn [acc [ns name]]
                 (update acc ns (fnil conj #{}) name))
               {})))

(defn build-edges
  "Build var-to-var edges with private attribution.
   
   For each public var, computes its 'responsibility set' (all private vars
   reachable through internal calls), then creates edges from the public var
   to any targets (internal or external) called by vars in the responsibility set.
   
   Returns a vector of {:from var-id, :to var-id} edges."
  [analysis]
  (let [var-defs (:var-definitions analysis)
        var-usages (:var-usages analysis)
        var-index (build-var-index var-defs)
        internal-calls (build-internal-call-graph var-usages var-index)
        external-calls (build-external-call-graph var-usages var-index)
        public-vars (public-vars-by-ns var-index)
        public-var-set (into #{} (for [[ns vars] public-vars, v vars] [ns v]))]
    (->> (for [ns-sym (keys public-vars)
               public-var (get public-vars ns-sym)
               :let [resp-set (responsibility-set public-var
                                                  (get internal-calls ns-sym {}))
                     ;; Collect all external calls from the responsibility set
                     external-targets (mapcat #(get external-calls [ns-sym %])
                                              resp-set)
                     ;; Collect internal calls to public vars from the responsibility set
                     internal-targets (->> resp-set
                                           (mapcat #(get-in internal-calls [ns-sym %] #{}))
                                           (filter #(contains? public-var-set [ns-sym %]))
                                           (remove #{public-var}) ; no self-loops
                                           (map #(vector ns-sym %)))
                     ;; Combine all targets
                     all-targets (concat external-targets internal-targets)]
               [target-ns target-var] all-targets
               ;; Only create edges to public vars
               :when (not (:private? (get var-index [target-ns target-var])))]
           {:from (var-id ns-sym public-var)
            :to (var-id target-ns target-var)})
         ;; Deduplicate edges
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
;; Index building

(defn build-model
  "Build the complete model from clj-kondo analysis.
   
   Returns a map containing:
   - :nodes - {id -> node} for all folders, namespaces, and vars
   - :edges - vector of {:from :to} var-level edges
   - :ns-edges - vector of {:from :to} namespace-level edges (aggregated from var edges)
   - :folder-edges - vector of {:from :to} folder-level edges (aggregated from ns edges)
   - :edges-by-from, :edges-by-to - var edge indexes
   - :ns-edges-by-from, :ns-edges-by-to - namespace edge indexes
   - :folder-edges-by-from, :folder-edges-by-to - folder edge indexes
   
   Note: Single-child folder chains are pruned - the root of the tree is the
   first folder with multiple children or non-folder children."
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

        ;; Build var-level edges with private attribution
        var-edges (build-edges analysis)

        ;; Build aggregated namespace-level edges
        ns-edges (build-ns-edges var-edges pruned-nodes)

        ;; Build aggregated folder-level edges
        folder-edges (build-folder-edges ns-edges pruned-nodes)]

    {:nodes pruned-nodes
     :edges var-edges
     :ns-edges ns-edges
     :folder-edges folder-edges
     :edges-by-from (group-by :from var-edges)
     :edges-by-to (group-by :to var-edges)
     :ns-edges-by-from (group-by :from ns-edges)
     :ns-edges-by-to (group-by :to ns-edges)
     :folder-edges-by-from (group-by :from folder-edges)
     :folder-edges-by-to (group-by :to folder-edges)}))
