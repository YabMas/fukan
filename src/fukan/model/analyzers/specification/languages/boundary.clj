(ns fukan.model.analyzers.specification.languages.boundary
  "Boundary-specific analysis that produces an AnalysisResult for the model
   build pipeline. Discovers .boundary files, parses them, and builds
   module nodes with surface data (provides, exposes, guarantees).
   Type definitions and schema nodes come from the allium analyzer."
  (:require [fukan.libs.boundary.parser :as parser]
            [fukan.model.analyzers :as analyzers]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; File utilities

(defn- file->parent-folder-path
  "Return the parent directory path for a boundary file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (str/join "/" (butlast parts))))

(defn- discover-boundary-files
  "Find .boundary files recursively under src-path."
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".boundary")))
       (mapv #(.getPath %))))

;; ---------------------------------------------------------------------------
;; Surface construction from boundary declarations

(defn- fn-decl->provides-entry
  "Convert a boundary fn declaration to the same shape as an allium
   provides entry, for compatibility with the build pipeline."
  [decl]
  (cond-> {:name (:name decl)}
    (:params decl) (assoc :params (mapv (fn [p]
                                          (cond-> {:name (:name p)}
                                            (:type p) (assoc :type (:type p))))
                                        (:params decl)))
    (:return decl) (assoc :return (:return decl))
    (:description decl) (assoc :description (:description decl))))

(defn- build-surface-from-boundary
  "Build a surface map from boundary declarations.
   Produces the same shape as allium's extract-surface, plus :exposes."
  [decls module-desc]
  (let [fn-decls (->> decls (filter #(= :fn (:type %))))
        guarantees (->> decls
                        (filter #(= :guarantee (:type %)))
                        (mapv :name))
        exposes (->> decls
                     (filter #(= :exposes (:type %)))
                     (mapv (fn [e]
                             (cond-> {:name (:name e)}
                               (:fields e) (assoc :fields (:fields e))))))
        provides-entries (mapv fn-decl->provides-entry fn-decls)]
    (cond-> {}
      module-desc (assoc :description module-desc)
      (seq provides-entries) (assoc :provides provides-entries)
      (seq exposes) (assoc :exposes exposes)
      (seq guarantees) (assoc :guarantees guarantees))))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-boundary-nodes
  "Build module nodes from parsed boundary files.
   Each file enriches its parent directory's module node with surface data
   (provides, exposes, guarantees). Schema nodes come from the allium analyzer."
  [_src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast text]}]
      (let [module-id (file->parent-folder-path filepath)
            decls (:declarations ast)
            module-desc (parser/extract-module-description text)
            surface (build-surface-from-boundary decls module-desc)

            module-node (cond->
                          {:id module-id
                           :kind :module
                           :label (last (str/split module-id #"/"))
                           :parent nil
                           :children #{}
                           :data (cond-> {:kind :module :has-spec true}
                                   (seq surface) (assoc :surface surface))}
                          module-desc (assoc :description module-desc))]
        (assoc acc module-id module-node)))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce a boundary language analysis result from source files.
   Discovers .boundary files under src-path, parses them, and builds
   an AnalysisResult with module nodes. No edges — cross-module
   schema references are derived by the build pipeline from allium
   schema nodes' TypeExpr keyword refs."
  {:malli/schema [:=> [:cat :FilePath] :AnalysisResult]}
  [src-path]
  (let [files (discover-boundary-files src-path)
        parsed (->> files
                    (map (fn [fp]
                           (let [text (slurp fp)
                                 ast (parser/parse-boundary text)]
                             {:filepath fp :ast ast :text text})))
                    (filter (fn [{:keys [ast]}]
                              (not (insta/failure? ast))))
                    vec)]
    (if (empty? parsed)
      {:source-files [] :nodes {} :edges []}
      {:source-files (mapv :filepath parsed)
       :nodes (build-boundary-nodes src-path parsed)
       :edges []})))

(defmethod analyzers/analyze :boundary [_ src-path]
  (analyze src-path))
