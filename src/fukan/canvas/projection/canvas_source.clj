(ns fukan.canvas.projection.canvas-source
  "Canvas ingestion: discover the defstructure-based canvas specs on the
   classpath, require them (registering their vocabulary and interning their
   instance `def`s), and assemble all those instance-vars into one structure db —
   which IS the model (design decision (ii): the structure substrate is the model;
   there is no model-map projection and no Phase-6 analyzer here anymore).

   Canvas namespaces are auto-discovered: any `canvas/**/*.clj` file on the
   classpath is a canvas port. A spec authors instances as top-level `def`s holding
   `InstanceValue`s; references between them are ordinary var references, resolved by
   the global assembler (no `build-canvas`, no merge/cross-ref pass). Adding a port is
   a single file drop. `union-dbs` remains only to fold an extractor's code db onto
   the assembled design db."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Discovery — scan canvas/ for *.clj and derive namespace symbols
;; ---------------------------------------------------------------------------

(defn- file->ns-segment
  "Convert a path segment from filename form to namespace form: strip a trailing
   .clj, then turn underscores into hyphens (the file→ns convention)."
  [seg]
  (-> seg (str/replace #"\.clj$" "") (str/replace #"_" "-")))

(defn- file->ns-symbol
  "Convert a canvas-root-relative file path into a namespace symbol, e.g.
   \"infra/model.clj\" → 'canvas.infra.model."
  [^String rel-path]
  (symbol (str "canvas." (str/join "." (mapv file->ns-segment (str/split rel-path #"/"))))))

(defn- canvas-root-dirs
  "Every `canvas/` directory on the classpath as a File (via the context
   ClassLoader's getResources), falling back to a relative `canvas/` lookup.
   nil when no canvas/ directory is locatable."
  []
  (let [cl       (.getContextClassLoader (Thread/currentThread))
        urls     (when cl (enumeration-seq (.getResources cl "canvas")))
        from-cp  (->> urls
                      (keep (fn [^java.net.URL u] (try (io/as-file u) (catch Exception _ nil))))
                      (filter #(and (some? %) (.isDirectory ^java.io.File %)))
                      vec)
        from-cwd (io/file "canvas")
        roots    (cond-> from-cp
                   (and (empty? from-cp) (.isDirectory from-cwd)) (conj from-cwd))]
    (when (seq roots) roots)))

(defn- discover-canvas-files-in
  "Yield {:root :rel-path} for every non-test `*.clj` under one canvas root."
  [^java.io.File root]
  (let [root-path (.getCanonicalPath root)]
    (->> (file-seq root)
         (filter (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (and (.isFile f) (str/ends-with? n ".clj")
                          (not (str/ends-with? n "_test.clj"))))))
         (map (fn [^java.io.File f]
                {:root root :rel-path (subs (.getCanonicalPath f) (inc (count root-path)))})))))

(defn- discover-canvas-namespaces
  "Sorted, distinct canvas namespace symbols across every canvas/ root. During
   the lean-kernel rebuild there may be no canvas/ root (specs pruned, new ones
   not yet authored) — then this returns [] with a one-line stderr note, so an
   empty model is never silent."
  []
  (if-let [roots (canvas-root-dirs)]
    (->> roots
         (mapcat discover-canvas-files-in)
         (map (fn [{:keys [rel-path]}] (file->ns-symbol rel-path)))
         distinct sort vec)
    (do (binding [*out* *err*]
          (println "canvas-source: no canvas/ root found — building an empty model"
                   "(lean-kernel rebuild phase)."))
        [])))

(defn- require-canvas-namespace
  "Require a canvas namespace (throwing on a load failure). Loading it registers its
   vocabulary (`defstructure`s) and interns its instance `def`s — both of which the
   global assembler then reads. A vocab-only spec interns no instances and simply
   contributes its grammar."
  [ns-sym]
  (try (require ns-sym)
       (catch Exception e
         (throw (ex-info (str "canvas-source: failed to load canvas namespace " ns-sym)
                         {:namespace ns-sym} e)))))

(defn canvas-namespaces
  "The auto-discovered canvas namespace symbols (public for inspection)."
  []
  (discover-canvas-namespaces))

(defn require-canvas-namespaces!
  "Discover every canvas namespace and REQUIRE it (registering its vocabulary and interning
   its instance `def`s), returning the ns symbols. The native Cozo build's instance-var scan
   (`model->cozo`) reads `ns-interns`, so the namespaces must be loaded first — this is that
   load step. (References between instances are ordinary var refs the assembler resolves; there
   is no merge/cross-ref pass.)"
  []
  (let [nss (discover-canvas-namespaces)]
    (doseq [ns-sym nss] (require-canvas-namespace ns-sym))
    nss))
