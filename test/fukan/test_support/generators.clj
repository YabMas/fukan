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
;; gen-analysis-data

(defn gen-analysis-data
  "Generate valid AnalysisData: namespace defs, var defs, var usages,
   and ns usages where all cross-references resolve."
  ([] (gen-analysis-data {}))
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
       (let [ns-defs (mapv (fn [ns-sym]
                             {:name ns-sym
                              :filename (str "src/" (str/replace (str ns-sym) "." "/") ".clj")})
                           ns-names)
             var-defs (vec (mapcat (fn [ns-sym var-names]
                                    (map (fn [vname]
                                           {:ns ns-sym
                                            :name (symbol vname)
                                            :filename (str "src/" (str/replace (str ns-sym) "." "/") ".clj")
                                            :row 1})
                                         var-names))
                                  ns-names var-name-lists))
             valid-pairs (vec (map (fn [{:keys [ns name]}] [ns name]) var-defs))]
         (gen/let [usage-count (gen/choose 0 (min 20 (* (count valid-pairs) 2)))
                   usage-from-idxs (gen/vector (gen/choose 0 (dec (count valid-pairs))) usage-count)
                   usage-to-idxs (gen/vector (gen/choose 0 (dec (count valid-pairs))) usage-count)
                   ns-usage-count (gen/choose 0 (min 10 (* (count ns-names) 2)))
                   ns-from-idxs (gen/vector (gen/choose 0 (dec (count ns-names))) ns-usage-count)
                   ns-to-idxs (gen/vector (gen/choose 0 (dec (count ns-names))) ns-usage-count)]
           (let [var-usages (->> (map (fn [fi ti]
                                        (let [[from-ns from-var] (nth valid-pairs fi)
                                              [to-ns to-var] (nth valid-pairs ti)]
                                          (when (not= [from-ns from-var] [to-ns to-var])
                                            {:from from-ns
                                             :from-var from-var
                                             :to to-ns
                                             :name to-var})))
                                      usage-from-idxs usage-to-idxs)
                                 (remove nil?)
                                 vec)
                 ns-usages (->> (map (fn [fi ti]
                                       (let [from-ns (nth ns-names fi)
                                             to-ns (nth ns-names ti)]
                                         (when (not= from-ns to-ns)
                                           {:from from-ns
                                            :to to-ns
                                            :filename (str "src/" (str/replace (str from-ns) "." "/") ".clj")})))
                                     ns-from-idxs ns-to-idxs)
                                (remove nil?)
                                vec)]
             {:namespace-definitions ns-defs
              :var-definitions var-defs
              :var-usages var-usages
              :namespace-usages ns-usages})))))))

;; ---------------------------------------------------------------------------
;; gen-contribution

(defn gen-contribution
  "Generate a valid Contribution with containers, functions, and edges.
   Container nodes have :parent nil and :filename in data (as expected by build-model).
   Function nodes have :parent pointing to their namespace container."
  ([] (gen-contribution {}))
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
                                           var-counts))]
       (let [source-files (mapv (fn [ns-sym]
                                  (str "src/" (str/replace (str ns-sym) "." "/") ".clj"))
                                ns-names)
             ns-nodes (into {} (map (fn [ns-sym filepath]
                                      (let [id (str ns-sym)]
                                        [id {:id id
                                             :kind :container
                                             :label id
                                             :parent nil
                                             :children #{}
                                             :data {:kind :container
                                                    :filename filepath}}]))
                                    ns-names source-files))
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
             all-fn-ids (vec (keys var-nodes))
             all-ns-ids (vec (map str ns-names))]
         (gen/let [edge-count (gen/choose 0 (min 20 (* (count all-fn-ids) 2)))
                   edge-from-idxs (gen/vector (gen/choose 0 (max 0 (dec (count all-fn-ids)))) edge-count)
                   edge-to-idxs (gen/vector (gen/choose 0 (max 0 (dec (count all-fn-ids)))) edge-count)
                   ns-edge-count (gen/choose 0 (min 10 (* (count all-ns-ids) 2)))
                   ns-from-idxs (gen/vector (gen/choose 0 (dec (count all-ns-ids))) ns-edge-count)
                   ns-to-idxs (gen/vector (gen/choose 0 (dec (count all-ns-ids))) ns-edge-count)]
           (let [var-edges (->> (map (fn [fi ti]
                                       (let [from (nth all-fn-ids fi)
                                             to (nth all-fn-ids ti)]
                                         (when (not= from to)
                                           {:from from :to to})))
                                     edge-from-idxs edge-to-idxs)
                                (remove nil?)
                                vec)
                 ns-edges (->> (map (fn [fi ti]
                                      (let [from (nth all-ns-ids fi)
                                            to (nth all-ns-ids ti)]
                                        (when (not= from to)
                                          {:from from :to to})))
                                    ns-from-idxs ns-to-idxs)
                               (remove nil?)
                               vec)]
             {:source-files source-files
              :nodes (merge ns-nodes var-nodes)
              :edges (vec (into (set var-edges) ns-edges))})))))))

;; ---------------------------------------------------------------------------
;; gen-model

(defn- make-container [id label parent-id]
  {:id id :kind :container :label label :parent parent-id :children #{}
   :data {:kind :container}})

(defn- make-function [id label parent-id private?]
  {:id id :kind :function :label label :parent parent-id :children #{}
   :data {:kind :function :private? private?}})

(defn gen-model
  "Generate a valid Model with tree structure and edges.
   Builds top-down: root → child containers → leaf functions → edges."
  ([] (gen-model {}))
  ([{:keys [min-containers max-containers min-fns-per-ns max-fns-per-ns]
     :or {min-containers 2 max-containers 5 min-fns-per-ns 1 max-fns-per-ns 4}}]
   (gen/let [container-count (gen/choose min-containers max-containers)
             container-names (gen/fmap (fn [names] (vec (take container-count (distinct names))))
                                       (gen/vector gen-simple-name (* container-count 2)))
             fn-counts (gen/vector (gen/choose min-fns-per-ns max-fns-per-ns)
                                   (count container-names))
             fn-name-lists (apply gen/tuple
                                  (mapv (fn [cnt]
                                          (gen/fmap (fn [names] (vec (take cnt (distinct names))))
                                                    (gen/vector gen-simple-name (max cnt (* cnt 2)))))
                                        fn-counts))
             private-flags-lists (apply gen/tuple
                                        (mapv (fn [cnt]
                                                (gen/vector gen/boolean cnt))
                                              fn-counts))]
     (let [root-id "root"
           root (make-container root-id "root" nil)
           containers (mapv (fn [name]
                              (make-container (str "ns:" name) name root-id))
                            container-names)
           fn-nodes (vec (mapcat (fn [container fn-names private-flags]
                                   (map (fn [fname priv?]
                                          (make-function
                                            (str (:id container) "/" fname)
                                            fname
                                            (:id container)
                                            priv?))
                                        fn-names private-flags))
                                 containers fn-name-lists private-flags-lists))
           all-raw (into {root-id root}
                         (map (fn [n] [(:id n) n]))
                         (concat containers fn-nodes))
           wired (reduce (fn [acc [id node]]
                           (if-let [pid (:parent node)]
                             (update-in acc [pid :children] conj id)
                             acc))
                         all-raw
                         all-raw)
           leaf-ids (mapv :id fn-nodes)]
       (gen/let [edge-count (gen/choose 0 (min 15 (* (count leaf-ids) 2)))
                 edge-from-idxs (gen/vector (gen/choose 0 (max 0 (dec (count leaf-ids)))) edge-count)
                 edge-to-idxs (gen/vector (gen/choose 0 (max 0 (dec (count leaf-ids)))) edge-count)]
         (let [edges (->> (map (fn [fi ti]
                                 (let [from-id (nth leaf-ids fi)
                                       to-id (nth leaf-ids ti)]
                                   (when (not= from-id to-id)
                                     {:from from-id :to to-id})))
                               edge-from-idxs edge-to-idxs)
                          (remove nil?)
                          distinct
                          vec)]
           {:nodes wired :edges edges}))))))

;; ---------------------------------------------------------------------------
;; gen-projection-opts

(defn gen-projection-opts
  "Generate valid projection options for a given model."
  [model]
  (let [container-ids (->> (vals (:nodes model))
                            (filter #(= :container (:kind %)))
                            (map :id)
                            vec)]
    (if (empty? container-ids)
      (gen/return {:view-id nil :expanded-containers #{}})
      (gen/let [view-idx (gen/choose 0 (dec (count container-ids)))
                expanded-flags (gen/vector gen/boolean (count container-ids))]
        {:view-id (nth container-ids view-idx)
         :expanded-containers (into #{} (keep-indexed
                                          (fn [i flag] (when flag (nth container-ids i)))
                                          expanded-flags))}))))

;; ---------------------------------------------------------------------------
;; gen-editor-state

(defn gen-editor-state
  "Generate an EditorState matching a given model and projection opts."
  [model opts]
  (let [all-ids (vec (keys (:nodes model)))]
    (if (empty? all-ids)
      (gen/return {:view-id nil :selected-id nil :schema-id nil :expanded-containers #{}})
      (gen/let [use-selected? gen/boolean
                selected-idx (gen/choose 0 (dec (count all-ids)))]
        {:view-id (:view-id opts)
         :selected-id (when use-selected? (nth all-ids selected-idx))
         :schema-id nil
         :expanded-containers (:expanded-containers opts)}))))
