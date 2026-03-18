(ns fukan.test-support.generators
  "Custom test.check generators for Fukan data structures.
   Produces structurally valid data by construction, not by filtering."
  (:require [clojure.test.check.generators :as gen]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Primitives

(def gen-simple-name
  "Generate a simple lowercase name (3-8 chars)."
  (gen/fmap (fn [chars] (apply str chars))
            (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz") 3 8)))

(def gen-ns-segment
  "Generate a namespace segment."
  gen-simple-name)

;; ---------------------------------------------------------------------------
;; gen-code-analysis

(defn gen-code-analysis
  "Generate valid CodeAnalysis: module defs, symbol defs, symbol refs,
   and module imports where all cross-references resolve."
  ([] (gen-code-analysis {}))
  ([{:keys [min-ns max-ns min-vars-per-ns max-vars-per-ns]
     :or {min-ns 2 max-ns 6 min-vars-per-ns 1 max-vars-per-ns 5}}]
   (gen/let [ns-count (gen/choose min-ns max-ns)
             base-segments (gen/vector gen-ns-segment ns-count)
             ns-names (gen/return
                        (mapv (fn [seg] (symbol (str "test." seg)))
                              (distinct base-segments)))
             ns-names (gen/return (if (< (count ns-names) 2)
                                   [(symbol "test.alpha") (symbol "test.beta")]
                                   ns-names))]
     (gen/let [var-counts (gen/vector (gen/choose min-vars-per-ns max-vars-per-ns)
                                      (count ns-names))
               var-name-lists (apply gen/tuple
                                     (mapv (fn [cnt]
                                             (gen/fmap (fn [names] (vec (take cnt (distinct names))))
                                                       (gen/vector gen-simple-name (max cnt (* cnt 2)))))
                                           var-counts))]
       (let [module-defs (mapv (fn [ns-sym]
                                 {:name ns-sym
                                  :filename (str "src/" (str/replace (str ns-sym) "." "/") ".clj")})
                               ns-names)
             symbol-defs (vec (mapcat (fn [ns-sym var-names]
                                       (map (fn [vname]
                                              {:module ns-sym
                                               :name (symbol vname)
                                               :filename (str "src/" (str/replace (str ns-sym) "." "/") ".clj")
                                               :row 1})
                                            var-names))
                                     ns-names var-name-lists))
             valid-pairs (vec (map (fn [{:keys [module name]}] [module name]) symbol-defs))]
         (gen/let [usage-count (gen/choose 0 (min 20 (* (count valid-pairs) 2)))
                   usage-from-idxs (gen/vector (gen/choose 0 (dec (count valid-pairs))) usage-count)
                   usage-to-idxs (gen/vector (gen/choose 0 (dec (count valid-pairs))) usage-count)
                   ns-usage-count (gen/choose 0 (min 10 (* (count ns-names) 2)))
                   ns-from-idxs (gen/vector (gen/choose 0 (dec (count ns-names))) ns-usage-count)
                   ns-to-idxs (gen/vector (gen/choose 0 (dec (count ns-names))) ns-usage-count)]
           (let [symbol-refs (->> (map (fn [fi ti]
                                         (let [[from-mod from-sym] (nth valid-pairs fi)
                                               [to-mod to-sym] (nth valid-pairs ti)]
                                           (when (not= [from-mod from-sym] [to-mod to-sym])
                                             {:from from-mod
                                              :from-symbol from-sym
                                              :to to-mod
                                              :name to-sym})))
                                       usage-from-idxs usage-to-idxs)
                                  (remove nil?)
                                  vec)
                 module-imports (->> (map (fn [fi ti]
                                           (let [from-ns (nth ns-names fi)
                                                 to-ns (nth ns-names ti)]
                                             (when (not= from-ns to-ns)
                                               {:from from-ns
                                                :to to-ns
                                                :filename (str "src/" (str/replace (str from-ns) "." "/") ".clj")})))
                                         ns-from-idxs ns-to-idxs)
                                    (remove nil?)
                                    vec)]
             {:module-definitions module-defs
              :symbol-definitions symbol-defs
              :symbol-references symbol-refs
              :module-imports module-imports})))))))

;; ---------------------------------------------------------------------------
;; gen-field, gen-surface

(def gen-field
  "Generate a valid Field map."
  (gen/let [name gen-simple-name
            type-ref (gen/elements ["String" "Integer" "Boolean" "List<String>" "Map<String, Node>"])
            has-desc? gen/boolean
            desc gen-simple-name]
    (cond-> {:name name :type-ref type-ref}
      has-desc? (assoc :description desc))))

(def gen-allium-type-ref
  "Generate a valid Allium type-ref for exercising allium-type-ref->schema."
  (gen/one-of
    [(gen/fmap (fn [n] {:kind :simple :name n})
               (gen/elements ["String" "Integer" "Boolean" "Model" "NodeId"]))
     (gen/fmap (fn [n] {:kind :generic :name "List"
                        :params [{:kind :simple :name n}]})
               (gen/elements ["String" "Node" "Edge"]))]))

(def gen-allium-provides
  "Generate a provides entry with :params and :return for exercising
   the full allium-provides->schema type conversion pipeline."
  (gen/let [name gen-simple-name
            param-count (gen/choose 0 3)
            param-names (gen/vector gen-simple-name param-count)
            param-types (gen/vector gen-allium-type-ref param-count)
            return-type gen-allium-type-ref
            has-desc? gen/boolean
            desc gen-simple-name]
    (cond-> {:name name
             :params (mapv (fn [n t] {:name n :type t}) param-names param-types)
             :return return-type}
      has-desc? (assoc :description desc))))

(def gen-surface
  "Generate a valid Surface with provides operations."
  (gen/let [has-facing? gen/boolean
            facing (gen/elements ["internal" "external"])
            has-desc? gen/boolean
            desc gen-simple-name
            provide-count (gen/choose 0 3)
            provides (gen/vector (gen/one-of [gen-field gen-allium-provides]) provide-count)
            guarantee-count (gen/choose 0 2)
            guarantees (gen/vector gen-simple-name guarantee-count)]
    (cond-> {}
      has-facing? (assoc :facing facing)
      has-desc? (assoc :description desc)
      (seq provides) (assoc :provides provides)
      (seq guarantees) (assoc :guarantees guarantees))))

;; ---------------------------------------------------------------------------
;; gen-analysis-result

(defn gen-analysis-result
  "Generate a valid AnalysisResult with modules, functions, and edges.
   Module nodes have :parent nil and :filename in data (as expected by build-model).
   Function nodes have :parent pointing to their namespace module."
  ([] (gen-analysis-result {}))
  ([{:keys [min-ns max-ns min-vars-per-ns max-vars-per-ns]
     :or {min-ns 2 max-ns 6 min-vars-per-ns 1 max-vars-per-ns 5}}]
   (gen/let [ns-count (gen/choose min-ns max-ns)
             base-segments (gen/vector gen-ns-segment ns-count)
             ns-names (gen/return (mapv #(symbol (str "test." %)) (distinct base-segments)))
             ns-names (gen/return (if (< (count ns-names) 2)
                                    [(symbol "test.alpha") (symbol "test.beta")]
                                    ns-names))]
     (gen/let [var-counts (gen/vector (gen/choose min-vars-per-ns max-vars-per-ns)
                                      (count ns-names))
               var-name-lists (apply gen/tuple
                                     (mapv (fn [cnt]
                                             (gen/fmap (fn [names] (vec (take cnt (distinct names))))
                                                       (gen/vector gen-simple-name (max cnt (* cnt 2)))))
                                           var-counts))
               ;; Sometimes add surfaces with provides to exercise materialization
               surface-flags (gen/vector (gen/frequency [[4 (gen/return false)]
                                                          [1 (gen/return true)]])
                                          (count ns-names))
               surfaces (apply gen/tuple
                               (mapv (fn [has-surface?]
                                       (if has-surface? gen-surface (gen/return nil)))
                                     surface-flags))]
       (let [source-files (mapv (fn [ns-sym]
                                  (str "src/" (str/replace (str ns-sym) "." "/") ".clj"))
                                ns-names)
             ns-nodes (into {} (map (fn [ns-sym filepath surface]
                                      (let [id (str ns-sym)
                                            short-label (last (str/split id #"\."))]
                                        [id {:id id
                                             :kind :module
                                             :label short-label
                                             :parent nil
                                             :children #{}
                                             :data (cond-> {:kind :module
                                                            :filename filepath}
                                                     surface (assoc :surface surface))}]))
                                    ns-names source-files surfaces))
             var-nodes (into {} (mapcat (fn [ns-sym var-names]
                                          (map (fn [vname]
                                                 (let [id (str ns-sym "/" vname)]
                                                   [id {:id id
                                                        :kind :function
                                                        :label vname
                                                        :parent (str ns-sym)
                                                        :children #{}
                                                        :data {:kind :function
                                                               :private? false}}]))
                                               var-names))
                                        ns-names var-name-lists))
             all-fn-ids (vec (keys var-nodes))]
         (gen/let [edge-count (gen/choose 0 (min 20 (* (count all-fn-ids) 2)))
                   edge-from-idxs (gen/vector (gen/choose 0 (max 0 (dec (count all-fn-ids)))) edge-count)
                   edge-to-idxs (gen/vector (gen/choose 0 (max 0 (dec (count all-fn-ids)))) edge-count)]
           (let [var-edges (->> (map (fn [fi ti]
                                       (let [from (nth all-fn-ids fi)
                                             to (nth all-fn-ids ti)]
                                         (when (not= from to)
                                           {:from from :to to :kind :function-call})))
                                     edge-from-idxs edge-to-idxs)
                                (remove nil?)
                                vec)]
             {:source-files source-files
              :nodes (merge ns-nodes var-nodes)
              :edges var-edges})))))))

;; ---------------------------------------------------------------------------
;; gen-model

(defn- make-module [id label parent-id]
  {:id id :kind :module :label label :parent parent-id :children #{}
   :data {:kind :module}})

(defn- make-function [id label parent-id private?]
  {:id id :kind :function :label label :parent parent-id :children #{}
   :data {:kind :function :private? private?}})

(defn- make-schema [id label parent-id schema-key]
  {:id id :kind :schema :label label :parent parent-id :children #{}
   :data {:kind :schema :schema-key schema-key :private? false}})

(defn gen-model
  "Generate a valid Model with tree structure, edges, and optional surfaces.
   Builds top-down: root → child modules → leaf functions → edges.
   When a surface has provides, corresponding function children are created."
  ([] (gen-model {}))
  ([{:keys [min-modules max-modules min-fns-per-ns max-fns-per-ns]
     :or {min-modules 2 max-modules 5 min-fns-per-ns 1 max-fns-per-ns 4}}]
   (gen/let [module-count (gen/choose min-modules max-modules)
             module-names (gen/fmap (fn [names] (vec (take module-count (distinct names))))
                                       (gen/vector gen-simple-name (* module-count 2)))
             fn-counts (gen/vector (gen/choose min-fns-per-ns max-fns-per-ns)
                                   (count module-names))
             fn-name-lists (apply gen/tuple
                                  (mapv (fn [cnt]
                                          (gen/fmap (fn [names] (vec (take cnt (distinct names))))
                                                    (gen/vector gen-simple-name (max cnt (* cnt 2)))))
                                        fn-counts))
             private-flags-lists (apply gen/tuple
                                        (mapv (fn [cnt]
                                                (gen/vector gen/boolean cnt))
                                              fn-counts))
             ;; Sometimes attach surfaces to modules
             surface-flags (gen/vector (gen/frequency [[3 (gen/return false)]
                                                       [1 (gen/return true)]])
                                       (count module-names))
             surfaces (apply gen/tuple
                             (mapv (fn [has-surface?]
                                     (if has-surface? gen-surface (gen/return nil)))
                                   surface-flags))
]
     (let [root-id "root"
           root (make-module root-id "root" nil)
           ;; Materialize surface provides as function children, strip provides
           modules (mapv (fn [name surface]
                              (let [stored-surface (when surface
                                                     (dissoc surface :provides))]
                                (cond-> (make-module (str "ns:" name) name root-id)
                                  (and stored-surface
                                       (seq (vals stored-surface)))
                                  (assoc-in [:data :boundary]
                                            (cond-> {:functions [] :schemas []}
                                              (:guarantees stored-surface) (assoc :guarantees (:guarantees stored-surface))
                                              (:description stored-surface) (assoc :description (:description stored-surface)))))))
                            module-names surfaces)
           ;; Function children from provides operations
           surface-fn-nodes (vec (mapcat
                                   (fn [module surface]
                                     (when-let [provides (seq (:provides surface))]
                                       (map (fn [{:keys [name description]}]
                                              (make-function
                                                (str (:id module) "/" name)
                                                name
                                                (:id module)
                                                false))
                                            provides)))
                                   modules surfaces))
           impl-fn-nodes (vec (mapcat (fn [module fn-names private-flags]
                                        (map (fn [fname priv?]
                                               (make-function
                                                 (str (:id module) "/" fname)
                                                 fname
                                                 (:id module)
                                                 priv?))
                                             fn-names private-flags))
                                      modules fn-name-lists private-flags-lists))
           ;; Merge, dedup by ID (impl wins over surface)
           fn-by-id (merge (into {} (map (fn [n] [(:id n) n]) surface-fn-nodes))
                           (into {} (map (fn [n] [(:id n) n]) impl-fn-nodes)))
           all-fn-nodes (vec (vals fn-by-id))]
       ;; Generate optional schema nodes per module
       (gen/let [schema-flags (gen/vector (gen/frequency [[2 (gen/return false)]
                                                           [1 (gen/return true)]])
                                          (count module-names))
                 schema-names (gen/vector gen-simple-name (count module-names))]
         (let [schema-nodes (vec (keep-indexed
                                   (fn [i has-schema?]
                                     (when has-schema?
                                       (let [module (nth modules i)
                                             sname (nth schema-names i)
                                             sid (str (:id module) "/" sname)]
                                         (make-schema sid sname (:id module) (keyword sname)))))
                                   schema-flags))
               all-leaf-nodes (concat all-fn-nodes schema-nodes)
               all-raw (into {root-id root}
                             (map (fn [n] [(:id n) n]))
                             (concat modules all-leaf-nodes))
               wired (reduce (fn [acc [id node]]
                               (if-let [pid (:parent node)]
                                 (update-in acc [pid :children] conj id)
                                 acc))
                             all-raw
                             all-raw)
               fn-ids (vec (map :id all-fn-nodes))
               schema-ids (vec (map :id schema-nodes))]
           (gen/let [fn-edge-count (gen/choose 0 (min 10 (* (count fn-ids) 2)))
                     fn-from-idxs (gen/vector (gen/choose 0 (max 0 (dec (count fn-ids)))) fn-edge-count)
                     fn-to-idxs (gen/vector (gen/choose 0 (max 0 (dec (count fn-ids)))) fn-edge-count)
                     fn-edge-kinds (gen/vector (gen/frequency [[3 (gen/return :function-call)]
                                                                [1 (gen/return :dispatches)]])
                                               fn-edge-count)
                     schema-edge-count (if (>= (count schema-ids) 2)
                                         (gen/choose 0 (min 5 (count schema-ids)))
                                         (gen/return 0))
                     schema-from-idxs (gen/vector (gen/choose 0 (max 0 (dec (max 1 (count schema-ids))))) (if (>= (count schema-ids) 2) 5 0))
                     schema-to-idxs (gen/vector (gen/choose 0 (max 0 (dec (max 1 (count schema-ids))))) (if (>= (count schema-ids) 2) 5 0))]
             (let [fn-edges (when (seq fn-ids)
                              (->> (map (fn [fi ti kind]
                                          (let [from-id (nth fn-ids fi)
                                                to-id (nth fn-ids ti)]
                                            (when (not= from-id to-id)
                                              {:from from-id :to to-id :kind kind})))
                                        fn-from-idxs fn-to-idxs fn-edge-kinds)
                                   (remove nil?)))
                   schema-edges (when (>= (count schema-ids) 2)
                                  (->> (map (fn [fi ti]
                                              (let [from-id (nth schema-ids fi)
                                                    to-id (nth schema-ids ti)]
                                                (when (not= from-id to-id)
                                                  {:from from-id :to to-id :kind :schema-reference})))
                                            schema-from-idxs schema-to-idxs)
                                       (remove nil?)))
                   edges (vec (distinct (concat fn-edges schema-edges)))]
               {:nodes wired :edges edges}))))))))

;; ---------------------------------------------------------------------------
;; gen-projection-opts

(defn gen-projection-opts
  "Generate valid projection options for a given model."
  [model]
  (let [module-ids (->> (vals (:nodes model))
                            (filter #(= :module (:kind %)))
                            (map :id)
                            vec)]
    (if (empty? module-ids)
      (gen/return {:view-id nil :expanded #{} :show-private #{}
                   :visible-edge-types #{:code-flow :schema-reference}})
      (gen/let [view-idx (gen/choose 0 (dec (count module-ids)))
                expanded-flags (gen/vector gen/boolean (count module-ids))
                show-private-flags (gen/vector gen/boolean (count module-ids))
                edge-type-choice (gen/elements [:both :code-flow-only :schema-reference-only])
                perspective (gen/elements [:call-graph :dependency-graph])]
        {:view-id (nth module-ids view-idx)
         :expanded (into #{} (keep-indexed
                               (fn [i flag] (when flag (nth module-ids i)))
                               expanded-flags))
         :show-private (into #{} (keep-indexed
                                   (fn [i flag] (when flag (nth module-ids i)))
                                   show-private-flags))
         :visible-edge-types (case edge-type-choice
                               :both #{:code-flow :schema-reference}
                               :code-flow-only #{:code-flow}
                               :schema-reference-only #{:schema-reference})
         :perspective perspective}))))

;; ---------------------------------------------------------------------------
;; gen-editor-state

(defn gen-editor-state
  "Generate an EditorState matching a given model and projection opts."
  [model opts]
  (let [all-ids (vec (keys (:nodes model)))]
    (if (empty? all-ids)
      (gen/return {:view-id nil :selected-id nil :schema-id nil :expanded #{} :show-private #{}})
      (gen/let [use-selected? gen/boolean
                selected-idx (gen/choose 0 (dec (count all-ids)))]
        {:view-id (:view-id opts)
         :selected-id (when use-selected? (nth all-ids selected-idx))
         :schema-id nil
         :expanded (:expanded opts)
         :show-private (:show-private opts)}))))
