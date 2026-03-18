(ns fukan.model.build
  "Model construction: runs the language-agnostic build pipeline to
   produce the graph model from analyzer results."
  (:require [clojure.string :as str]
            [fukan.model.analyzers :as analyzers]
            [fukan.model.schema]
            [fukan.utils.files]))

;; Require model.schema and utils.files so all ^:schema vars are loaded
;; for the registry. No alias needed — schemas are referenced by keyword.

;; -----------------------------------------------------------------------------
;; Pipeline Schemas

(def ^:schema AnalysisResult
  [:map {:description "A language analysis result: pre-built nodes and edges ready for the build pipeline. Each language analyzer produces an AnalysisResult; results are merged before calling build-model."}
   [:source-files {:description "File paths for folder hierarchy construction."} [:vector :FilePath]]
   [:nodes {:description "Pre-built nodes. Module nodes should have :parent nil and :filename in :data for folder parenting."} [:map-of :NodeId :Node]]
   [:edges {:description "Pre-built edges between nodes."} [:vector :Edge]]])

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
                    label (last (str/split dir-path #"/"))
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
;; Companion file collapse

(defn- strip-extension
  "Remove the file extension from a path."
  [filepath]
  (let [idx (str/last-index-of filepath ".")]
    (if idx (subs filepath 0 idx) filepath)))

(defn- collapse-companion-files
  "Collapse companion file pairs: when a module's source file (e.g. foo.clj)
   has a matching directory (foo/), the module node absorbs the folder node.
   Children of the folder are re-parented to the module, and the folder is removed.
   This handles the Clojure convention of foo.clj alongside foo/ for the same namespace."
  [nodes folder-ids]
  (let [;; Build map: folder-path -> module-id for companion pairs
        companions (->> (vals nodes)
                        (filter #(and (= :module (:kind %))
                                      (not (contains? folder-ids (:id %)))
                                      (get-in % [:data :filename])))
                        (reduce (fn [acc node]
                                  (let [folder-path (strip-extension (get-in node [:data :filename]))]
                                    (if (contains? folder-ids folder-path)
                                      (assoc acc folder-path (:id node))
                                      acc)))
                                {}))]
    (if (empty? companions)
      nodes
      (reduce-kv
        (fn [acc folder-path module-id]
          (let [folder-node (get acc folder-path)]
            (-> acc
                ;; Set module's parent to folder's parent
                (assoc-in [module-id :parent] (:parent folder-node))
                ;; Re-parent folder's children to module
                (as-> m
                  (reduce (fn [m [id node]]
                            (if (= (:parent node) folder-path)
                              (assoc-in m [id :parent] module-id)
                              m))
                          m m))
                ;; Remove the folder node
                (dissoc folder-path))))
        nodes
        companions))))

;; -----------------------------------------------------------------------------
;; Surface-to-boundary collapse

(def ^:private allium-primitive-types
  "Allium type names that map to primitive schema tags."
  {"String" "string" "Integer" "int" "Boolean" "boolean"})

(defn- allium-type-ref->schema
  "Convert an Allium type-ref AST node to the internal schema format
   used by boundary function signatures. This allows spec-declared
   types to produce the same schema representation as malli-derived
   types from the implementation."
  [type-ref]
  (when type-ref
    (case (:kind type-ref)
      :simple
      (if-let [prim (allium-primitive-types (:name type-ref))]
        {:tag :primitive :name prim}
        {:tag :ref :name (keyword (:name type-ref))})

      :optional
      {:tag :maybe :inner (allium-type-ref->schema (:inner type-ref))}

      :generic
      (let [n (:name type-ref)
            params (mapv allium-type-ref->schema (:params type-ref))]
        (case n
          "List"  {:tag :vector :element (first params)}
          "Set"   {:tag :set :element (first params)}
          "Map"   {:tag :map-of :key-type (first params) :value-type (second params)}
          ;; Unknown generic — use ref with params
          {:tag :ref :name (keyword n)}))

      :union
      {:tag :or :variants (mapv allium-type-ref->schema (:members type-ref))}

      :qualified
      {:tag :ref :name (keyword (:ns type-ref) (:name type-ref))}

      nil)))

(defn- allium-provides->schema
  "Convert Allium provides entry params and return type to a function
   signature schema {:inputs [...] :output ...}."
  [{:keys [params return]}]
  (when (or (seq params) return)
    (cond-> {}
      (seq params) (assoc :inputs (mapv #(allium-type-ref->schema (:type %)) params))
      return       (assoc :output (allium-type-ref->schema return)))))

(defn- descendant-fns-by-label
  "Collect all Function descendants (any depth) of a module, indexed by label.
   On label collision, prefers nodes with signatures over placeholders."
  [nodes module-id]
  (->> (vals nodes)
       (filter #(= :function (:kind %)))
       (filter (fn [node]
                 (loop [pid (:parent node)]
                   (cond
                     (nil? pid) false
                     (= pid module-id) true
                     :else (recur (:parent (get nodes pid)))))))
       (reduce (fn [acc node]
                 (let [existing (get acc (:label node))]
                   (if (and existing (get-in existing [:data :signature])
                            (not (get-in node [:data :signature])))
                     acc
                     (assoc acc (:label node) node))))
               {})))

(defn- surface-provides->boundary-fns
  "Convert surface :provides entries into boundary function declarations.
   Matches provides names against Function descendant nodes (not just
   direct children) to pull in signatures and docs from the implementation.
   Descendants are searched because the surface may be on a directory-level
   module while functions live in namespace-level child modules.
   Provides names use underscores; implementation labels use hyphens —
   both conventions are tried for matching."
  [nodes module-id provides]
  (when (seq provides)
    (let [desc-fns (descendant-fns-by-label nodes module-id)]
      (->> provides
           (mapv (fn [entry]
                   (let [{:keys [name description]} entry
                         ;; Try both underscore→hyphen and exact match
                         hyphen-name (str/replace name "_" "-")
                         match (or (get desc-fns hyphen-name)
                                   (get desc-fns name))
                         ;; Prefer implementation schema, fall back to spec-declared types
                         impl-schema (get-in match [:data :signature])
                         spec-schema (allium-provides->schema entry)
                         schema (or impl-schema spec-schema)]
                     (cond-> {:name (or (:label match) hyphen-name)}
                       (:id match) (assoc :id (:id match))
                       schema (assoc :signature schema)
                       (or description (get-in match [:data :doc])) (assoc :doc (or description (get-in match [:data :doc])))))))))))

(defn- collapse-surface-to-boundary
  "Collapse remaining :surface data into :boundary for each module.
   After materialization, :surface may still contain :guarantees,
   :description, and :provides. Provides entries are reconciled against
   existing Function children to build boundary function declarations.
   These are merged into :boundary, then :surface is removed."
  [nodes]
  (reduce-kv
    (fn [acc id node]
      (if-let [surface (get-in node [:data :surface])]
        (let [provides-fns (surface-provides->boundary-fns nodes id (:provides surface))
              boundary-parts (cond-> {}
                               (:guarantees surface) (assoc :guarantees (:guarantees surface))
                               (:description surface) (assoc :description (:description surface))
                               (seq provides-fns) (assoc :functions provides-fns))
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

(defn- infer-module-boundary
  "Infer a boundary for a module from its child function and schema nodes.
   Reads signatures from already-built nodes instead of going to runtime.
   Excludes schema-defining symbols (they have corresponding schema nodes).
   Collects :schema-key values from schema children into :schemas.

   Relies on the node ID convention: function node IDs are 'parent-id/label',
   matching the ID that schema-defining symbols would have."
  [nodes parent-id]
  (let [description (get-in nodes [parent-id :data :doc])
        schema-children (->> (vals nodes)
                             (filter #(and (= :schema (:kind %))
                                           (= parent-id (:parent %)))))
        schema-symbol-ids (into #{} (map (fn [sn] (str parent-id "/" (:label sn)))) schema-children)
        schemas (into [] (keep #(get-in % [:data :schema-key])) schema-children)
        functions (->> (vals nodes)
                       (filter #(and (= :function (:kind %))
                                     (= parent-id (:parent %))
                                     (not (get-in % [:data :private?]))
                                     (not (contains? schema-symbol-ids (:id %)))))
                       (mapv (fn [node]
                               (cond-> {:name (:label node)
                                        :id (:id node)}
                                 (get-in node [:data :signature])
                                 (assoc :signature (get-in node [:data :signature]))
                                 (get-in node [:data :doc])
                                 (assoc :doc (get-in node [:data :doc]))))))]
    {:description description
     :functions (or (not-empty functions) [])
     :schemas (or (not-empty schemas) [])}))

(defn- attach-boundary
  "Attach or merge a boundary into a node's :data map.
   Always ensures a boundary exists (may be empty).
   When both existing (from surface) and inferred boundaries have :functions,
   the existing functions take priority — they are the explicit contract.
   Inferred functions are only used when no surface declares them."
  [node boundary]
  (let [existing (get-in node [:data :boundary])
        ;; Remove nil values from inferred boundary so it doesn't
        ;; overwrite explicit values (e.g. surface :description)
        boundary (when boundary
                   (into {} (remove (comp nil? val)) boundary))
        ;; If existing already has :functions (from surface provides),
        ;; don't let inferred :functions overwrite them
        boundary (if (and (seq (:functions existing)) (:functions boundary))
                   (dissoc boundary :functions)
                   boundary)
        defaults {:functions [] :schemas []}
        merged (cond
                 (and (seq existing) (seq boundary)) (merge defaults existing boundary)
                 (seq existing) (merge defaults existing)
                 (seq boundary) (merge defaults boundary)
                 :else (assoc defaults :description nil))]
    (update node :data #(assoc (or % {}) :boundary merged))))

(defn- attach-boundaries
  "Attach boundaries to module nodes.
   Infers boundaries from child function signatures and merges into
   any existing boundary (e.g. from spec surface provides or guarantees).
   Returns updated nodes map."
  [nodes]
  (reduce (fn [acc [id node]]
            (if (= :module (:kind node))
              (let [boundary (infer-module-boundary nodes id)]
                (assoc acc id (attach-boundary node boundary)))
              (assoc acc id node)))
          {}
          nodes))

;; -----------------------------------------------------------------------------
;; Schema-defining symbol removal

(defn- remove-schema-defining-symbols
  "Remove function nodes whose symbols define schemas.
   These are redundant — the schema node represents them.

   Relies on the node ID convention: function node IDs are 'parent-id/label',
   matching the ID that schema-defining symbols would have."
  [nodes]
  (let [schema-symbol-ids (->> (vals nodes)
                               (filter #(= :schema (:kind %)))
                               (map (fn [sn] (str (:parent sn) "/" (:label sn))))
                               set)
        cleaned (apply dissoc nodes schema-symbol-ids)]
    ;; Also remove from parent children sets
    (reduce (fn [acc symbol-id]
              (let [parent-id (:parent (get nodes symbol-id))]
                (if (and parent-id (contains? acc parent-id))
                  (update-in acc [parent-id :children] disj symbol-id)
                  acc)))
            cleaned
            schema-symbol-ids)))

;; -----------------------------------------------------------------------------
;; Schema-reference edge construction

(defn- collect-type-expr-refs
  "Recursively collect :ref keyword names from a TypeExpr tree.
   Returns a seq of keywords. Handles both tagged TypeExpr nodes
   and bare function signatures (which have :inputs/:output but no :tag)."
  [type-expr]
  (when (map? type-expr)
    (if-let [tag (:tag type-expr)]
      (case tag
        :ref [(:name type-expr)]
        :primitive []
        :map (mapcat (fn [entry] (collect-type-expr-refs (:type entry)))
                     (:entries type-expr))
        :map-of (concat (collect-type-expr-refs (:key-type type-expr))
                        (collect-type-expr-refs (:value-type type-expr)))
        :vector (collect-type-expr-refs (:element type-expr))
        :set (collect-type-expr-refs (:element type-expr))
        :maybe (collect-type-expr-refs (:inner type-expr))
        :or (mapcat collect-type-expr-refs (:variants type-expr))
        :and (mapcat collect-type-expr-refs (:types type-expr))
        :tuple (mapcat collect-type-expr-refs (:elements type-expr))
        :fn (concat (mapcat collect-type-expr-refs (:inputs type-expr))
                    (collect-type-expr-refs (:output type-expr)))
        :enum []
        :predicate []
        :unknown []
        [])
      ;; Bare function signature: {:inputs [...] :output {...}}
      (when (and (:inputs type-expr) (:output type-expr))
        (concat (mapcat collect-type-expr-refs (:inputs type-expr))
                (collect-type-expr-refs (:output type-expr)))))))

(defn- build-schema-ref-edges
  "Create edges from schema-to-schema TypeExpr keyword references.
   Walks each schema node's :schema TypeExpr and creates edges to
   any referenced schema nodes that exist in the model.
   Function→Schema edges are omitted — type annotations are not
   meaningful dependencies and create false hub nodes."
  [nodes]
  (let [;; Build index: schema-key keyword -> node-id
        schema-key->id (->> (vals nodes)
                            (filter #(= :schema (:kind %)))
                            (reduce (fn [acc node]
                                      (if-let [sk (get-in node [:data :schema-key])]
                                        (assoc acc sk (:id node))
                                        acc))
                                    {}))
        ;; Schema -> Schema edges (from :schema TypeExpr)
        schema-edges (->> (vals nodes)
                          (filter #(= :schema (:kind %)))
                          (mapcat (fn [node]
                                    (when-let [schema (get-in node [:data :schema])]
                                      (let [refs (collect-type-expr-refs schema)]
                                        (->> refs
                                             (keep schema-key->id)
                                             (remove #(= % (:id node)))
                                             (map (fn [to] {:from (:id node) :to to :kind :schema-reference}))))))))]
    (vec (into #{} schema-edges))))

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
   - Searches descendants (any depth) for matching functions
   - If a descendant match exists, enriches its :doc but does NOT create duplicates
   - Creates a new Function child only if no descendant match is found
   Provides names use underscores; implementation labels use hyphens.
   Both conventions are tried when matching."
  [nodes]
  (reduce-kv
    (fn [acc id node]
      (if-let [provides (seq (get-in node [:data :surface :provides]))]
        (let [;; Search all descendants, not just direct children
              desc-fns (descendant-fns-by-label nodes id)
              ;; Create or enrich for each provides entry
              {new-nodes :nodes enrichments :enrichments}
              (reduce
                (fn [acc {:keys [name description]}]
                  (let [hyphen-name (str/replace name "_" "-")
                        match (or (get desc-fns hyphen-name)
                                  (get desc-fns name))]
                    (if match
                      ;; Match: enrich existing function's doc
                      (update acc :enrichments conj [(:id match) description])
                      ;; No match: create new Function child with hyphenated label
                      (let [fn-id (str id "/" hyphen-name)
                            fn-node {:id fn-id
                                     :kind :function
                                     :label hyphen-name
                                     :parent id
                                     :children #{}
                                     :data {:kind :function
                                            :private? false
                                            :doc description}}]
                        (update acc :nodes assoc fn-id fn-node)))))
                {:nodes {} :enrichments []}
                provides)
              ;; Keep :provides in surface — collapse-surface-to-boundary
              ;; will use them for boundary function declarations
              updated-node node]
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

(defn- run-pipeline
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

        ;; Collapse companion file pairs (foo.clj + foo/ -> single module)
        collapsed-nodes (collapse-companion-files merged-nodes folder-ids)

        ;; Remove empty modules
        cleaned-nodes (remove-empty-modules collapsed-nodes)

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

        ;; Remove symbol nodes that define schemas — they're represented by schema nodes
        final-nodes (remove-schema-defining-symbols materialized-nodes)

        ;; Build schema-reference edges from TypeExpr keyword refs
        schema-ref-edges (build-schema-ref-edges final-nodes)

        ;; Filter edges to surviving nodes, deduplicate
        node-ids (set (keys final-nodes))
        all-edges (->> (concat result-edges schema-ref-edges)
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
  "Build complete model from a source path and a set of analyzer keys.
   Each key dispatches to a registered analyzer via the analyze multimethod.
   Runs all analyzers, merges their results, and produces
   the final Model through the language-agnostic build pipeline."
  {:malli/schema [:=> [:cat :FilePath [:set :AnalyzerKey]] :Model]}
  [src-path analyzer-keys]
  (let [results (map #(analyzers/analyze % src-path) analyzer-keys)
        merged  (apply merge-results results)]
    (run-pipeline merged)))
