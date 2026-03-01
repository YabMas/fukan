(ns fukan.model.build
  "Model construction: orchestrates language analyzers and runs the
   language-agnostic build pipeline to produce the graph model."
  (:require [clojure.string :as str]
            [fukan.model.schema]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.model.analyzers.specification.languages.allium :as allium]))

;; Require model.schema so its ^:schema vars are loaded for the registry.
;; No alias needed — schemas are referenced by keyword, not by var.

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

(defn- has-spec-data?
  "True if a module node has a boundary declaration."
  [node]
  (:boundary (:data node)))

(defn- remove-empty-modules
  "Remove module nodes that have no children.
   Exception: modules with a boundary declaration are retained
   even without children — they represent data-shape definitions."
  [nodes]
  (let [;; First, wire children to see which modules are empty
        wired (wire-children nodes)
        ;; Find modules with no children and no spec data
        empty-ids (->> wired
                       (filter (fn [[_id node]]
                                 (and (= :module (:kind node))
                                      (empty? (:children node))
                                      (not (has-spec-data? node)))))
                       (map first)
                       set)]
    ;; Remove empty modules
    (apply dissoc nodes empty-ids)))

(defn- find-smart-root
  "Find a smart starting module by skipping single-child folder modules.
   folder-ids is the set of node IDs that were created as directory nodes.
   Returns the ID of the deepest folder that has multiple children
   or non-folder children."
  [nodes folder-ids]
  (loop [module-id nil]
    (let [children (->> (vals nodes)
                        (filter (fn [node]
                                  (if module-id
                                    (= (:parent node) module-id)
                                    ;; Root level: no parent or parent not in nodes
                                    (let [p (:parent node)]
                                      (or (nil? p) (not (contains? nodes p)))))))
                        (remove #(= (:kind %) :function)))] ; Modules only
      ;; If exactly one child and it's a folder, descend
      (if (and (= 1 (count children))
               (contains? folder-ids (:id (first children))))
        (recur (:id (first children)))
        module-id))))

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
                          :kind :module
                          :label label
                          :parent parent-id
                          :children #{}
                          :data {:kind :module}}]
                (-> acc
                    (assoc-in [:nodes id] node)
                    (assoc-in [:index dir-path] id))))
            {:nodes {} :index {}}
            ;; Sort dirs so parents are processed first
            (sort-by count all-dirs))))

(defn- file-to-folder
  "Get the folder path for a file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (when (> (count parts) 1)
      (str/join "/" (butlast parts)))))

;; -----------------------------------------------------------------------------
;; Surface-to-boundary collapse

(defn- collapse-surface-to-boundary
  "Collapse remaining :surface data into :boundary for each module.
   After materialization, :surface may still contain :guarantees and :description.
   These are merged into :boundary, then :surface is removed."
  [nodes]
  (reduce-kv
    (fn [acc id node]
      (if-let [surface (get-in node [:data :surface])]
        (let [boundary-parts (cond-> {}
                               (:guarantees surface) (assoc :guarantees (:guarantees surface))
                               (:description surface) (assoc :description (:description surface)))
              existing-boundary (get-in node [:data :boundary])
              merged-boundary (if existing-boundary
                                (merge existing-boundary boundary-parts)
                                (when (seq boundary-parts) boundary-parts))
              updated-data (-> (:data node)
                               (dissoc :surface)
                               (cond-> merged-boundary (assoc :boundary merged-boundary)))]
          (assoc acc id (assoc node :data updated-data)))
        (assoc acc id node)))
    {} nodes))

;; -----------------------------------------------------------------------------
;; Boundary construction

(defn- infer-namespace-boundary
  "Infer a boundary for a namespace from its child function and schema nodes.
   Reads signatures from already-built nodes instead of going to runtime.
   Excludes schema-defining vars (they have corresponding schema nodes).
   Collects :schema-key values from schema children into :schemas."
  [nodes ns-id]
  (let [description (get-in nodes [ns-id :data :doc])
        schema-children (->> (vals nodes)
                             (filter #(and (= :schema (:kind %))
                                           (= ns-id (:parent %)))))
        schema-var-ids (into #{} (map (fn [sn] (str ns-id "/" (:label sn)))) schema-children)
        schemas (into [] (keep #(get-in % [:data :schema-key])) schema-children)
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
    (when (or (seq functions) (seq schemas))
      (cond-> {:description description}
        (seq functions) (assoc :functions functions)
        (seq schemas) (assoc :schemas schemas)))))

(defn- attach-boundary
  "Attach or merge a boundary into a node's :data map."
  [node boundary]
  (if boundary
    (let [existing (get-in node [:data :boundary])]
      (if existing
        (update-in node [:data :boundary] merge boundary)
        (update node :data #(assoc (or % {}) :boundary boundary))))
    node))

(defn- attach-boundaries
  "Attach namespace boundaries to module nodes.
   Infers boundaries from child function signatures and merges into
   any existing boundary (e.g. from spec guarantees or contract.edn).
   Returns updated nodes map."
  [nodes]
  (reduce (fn [acc [id node]]
            (if (= :module (:kind node))
              (let [boundary (infer-namespace-boundary nodes id)]
                (assoc acc id (attach-boundary node boundary)))
              (assoc acc id node)))
          {}
          nodes))

;; -----------------------------------------------------------------------------
;; Schema-defining var removal

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
;; AnalysisResult merging

(defn- merge-node-pair
  "Merge two nodes with the same ID. Deep-merges :data maps for modules
   so that spec data (boundary, description) enriches impl data (doc)
   rather than overwriting it. Non-module nodes or nodes with different
   kinds use simple last-wins merge."
  [a b]
  (if (and (= :module (:kind a)) (= :module (:kind b)))
    (-> (merge a b)
        (assoc :data (merge (:data a) (:data b)))
        (assoc :parent (or (:parent b) (:parent a)))
        (update :children (fn [c] (into (or (:children a) #{}) (or c #{})))))
    b))

(defn- merge-node-maps
  "Merge multiple node maps with deep merge for shared module IDs."
  [& nmaps]
  (reduce (fn [acc nmap]
            (reduce-kv (fn [m id node]
                         (if-let [existing (get m id)]
                           (assoc m id (merge-node-pair existing node))
                           (assoc m id node)))
                       acc nmap))
          {} nmaps))

(defn- merge-results
  "Merge multiple language analysis results into one.
   Module nodes with the same ID are deep-merged (spec enriches impl).
   Other nodes use last-wins. Edges are deduplicated."
  [& results]
  {:source-files (vec (mapcat :source-files results))
   :nodes (apply merge-node-maps (map :nodes results))
   :edges (vec (into #{} (mapcat :edges results)))})

;; -----------------------------------------------------------------------------
;; Surface materialization

(defn- materialize-surface-functions
  "Materialize surface provides operations as Function child nodes.
   For each module with surface.provides:
   - Creates a Function child if no existing child has a matching label
   - Enriches an existing function's :doc if it matches by name
   Strips :provides from the stored surface afterward."
  [nodes]
  (reduce-kv
    (fn [acc id node]
      (if-let [provides (seq (get-in node [:data :surface :provides]))]
        (let [;; Collect existing child labels for matching
              child-labels (->> (:children node)
                                (keep #(get nodes %))
                                (filter #(= :function (:kind %)))
                                (map :label)
                                set)
              ;; Create or enrich for each provides entry
              {new-nodes :nodes enrichments :enrichments}
              (reduce
                (fn [acc {:keys [name description]}]
                  (if (contains? child-labels name)
                    ;; Match: enrich existing function's doc
                    (let [match-id (str id "/" name)]
                      (update acc :enrichments conj [match-id description]))
                    ;; No match: create new Function child
                    (let [fn-id (str id "/" name)
                          fn-node {:id fn-id
                                   :kind :function
                                   :label name
                                   :parent id
                                   :children #{}
                                   :data {:kind :function
                                          :private? false
                                          :doc description}}]
                      (update acc :nodes assoc fn-id fn-node))))
                {:nodes {} :enrichments []}
                provides)
              ;; Strip :provides from stored surface
              updated-node (update-in node [:data :surface] dissoc :provides)]
          (-> acc
              (assoc id updated-node)
              ;; Add new function nodes
              (merge new-nodes)
              ;; Apply enrichments to existing nodes
              (as-> m
                (reduce (fn [m [fn-id desc]]
                          (if (and desc (contains? m fn-id)
                                   (nil? (get-in m [fn-id :data :doc])))
                            (assoc-in m [fn-id :data :doc] desc)
                            m))
                        m enrichments))))
        (assoc acc id node)))
    {} nodes))

;; -----------------------------------------------------------------------------
;; Build pipeline

(defn run-pipeline
  "Run the language-agnostic build pipeline on a merged analysis result.
   Transforms flat analysis nodes into a tree model with folder hierarchy,
   boundaries, and pruned structure."
  {:malli/schema [:=> [:cat :AnalysisResult] :Model]}
  [result]
  (let [source-files (:source-files result)
        result-nodes (:nodes result)
        result-edges (:edges result)

        ;; Build folder hierarchy from source files
        {:keys [nodes index]} (build-folder-nodes-from-files source-files)
        folder-nodes nodes
        folder-index index
        folder-ids (set (keys folder-nodes))

        ;; Set parents on result's module nodes from :filename in data
        parented-nodes (reduce-kv
                         (fn [acc id node]
                           (if (and (= :module (:kind node))
                                    (nil? (:parent node)))
                             (let [filename (get-in node [:data :filename])
                                   folder-path (when filename (file-to-folder filename))
                                   parent-id (when folder-path (get folder-index folder-path))]
                               (assoc acc id (assoc node :parent parent-id)))
                             (assoc acc id node)))
                         {} result-nodes)

        ;; Merge folder nodes + result nodes (deep merge preserves
        ;; folder parents when enrichment nodes have :parent nil)
        merged-nodes (merge-node-maps folder-nodes parented-nodes)

        ;; Remove empty modules
        cleaned-nodes (remove-empty-modules merged-nodes)

        ;; Wire up parent-child relationships
        all-nodes (wire-children cleaned-nodes)

        ;; Find smart starting point and prune ancestors
        smart-root (find-smart-root all-nodes folder-ids)
        pruned-nodes (prune-to-smart-root all-nodes smart-root)

        ;; Materialize surface provides as Function children, collapse
        ;; remaining surface data into boundary, re-wire
        materialized-nodes (-> (materialize-surface-functions pruned-nodes)
                               collapse-surface-to-boundary
                               wire-children)

        ;; Remove var nodes that define schemas — they're represented by schema nodes
        final-nodes (remove-schema-defining-vars materialized-nodes)

        ;; Filter edges to surviving nodes, deduplicate
        node-ids (set (keys final-nodes))
        all-edges (->> result-edges
                       (filter (fn [{:keys [from to]}]
                                 (and (contains? node-ids from)
                                      (contains? node-ids to)
                                      (not= from to))))
                       (into #{})
                       vec)

        ;; Attach boundaries to modules
        boundary-nodes (attach-boundaries final-nodes)]

    {:nodes boundary-nodes
     :edges all-edges}))

;; -----------------------------------------------------------------------------
;; Public API

(defn build-model
  "Build complete model from a source path.
   Runs all language analyzers, merges their results, and produces
   the final Model through the language-agnostic build pipeline."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [clj-result    (clj-lang/analyze src-path)
        allium-result (allium/analyze src-path)
        merged        (merge-results clj-result allium-result)]
    (run-pipeline merged)))
