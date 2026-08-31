(ns fukan.canvas.ingestion.canvas-source
  "Canvas ingestion: discover the defstructure-based canvas specs on the
   classpath, require them (registering their vocabulary and interning their
   instance `def`s), and assemble all those instance-vars into one structure db —
   which IS the model (design decision (ii): the structure substrate is the model;
   there is no model-map projection and no Phase-6 analyzer here anymore).

   Canvas namespaces are auto-discovered: any `canvas/**/*.clj` file on the
   classpath is a canvas port. A spec authors instances as top-level `def`s holding
   `InstanceValue`s; references between them are ordinary var references, resolved by
   the global assembler (no `build-canvas`, no merge/cross-ref pass). Adding a port is
   a single file drop. The native builder assembles authored and extracted facts into
   one Cozo db."
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

(def ^:dynamic *spec-dirs*
  "Classpath dir names scanned for instance-bearing specs. Each name is BOTH the
   resource path scanned AND the leading namespace segment: `<dir>/a/b.clj` → ns
   `<dir>.a.b`. fukan-on-itself keeps the default `[\"canvas\"]` (its self-model);
   a consuming project binds this to its own spec dir(s)."
  ["canvas"])

(defn- file->ns-symbol
  "A spec dir NAME + a dir-relative file path → the namespace symbol,
   e.g. \"canvas\" + \"architecture/kernel/structure.clj\" → 'canvas.architecture.kernel.structure."
  [dir ^String rel-path]
  (symbol (str dir "." (str/join "." (mapv file->ns-segment (str/split rel-path #"/"))))))

(defn- spec-root-dirs
  "For one spec dir NAME, every such directory on the classpath (via the context
   ClassLoader's getResources), falling back to a relative lookup. Yields
   [{:dir dir :root File} …]; empty when none is locatable."
  [dir]
  (let [cl       (.getContextClassLoader (Thread/currentThread))
        urls     (when cl (enumeration-seq (.getResources cl dir)))
        from-cp  (->> urls
                      (keep (fn [^java.net.URL u] (try (io/as-file u) (catch Exception _ nil))))
                      (filter #(and (some? %) (.isDirectory ^java.io.File %)))
                      vec)
        from-cwd (io/file dir)
        roots    (cond-> from-cp
                   (and (empty? from-cp) (.isDirectory from-cwd)) (conj from-cwd))]
    (map (fn [r] {:dir dir :root r}) roots)))

(defn- discover-canvas-files-in
  "Yield {:root :rel-path} for every `*.clj` under one canvas root.

   EVERY one. A spec directory is declared, not guessed at — `*spec-dirs*` names it — so its
   contents are specs by construction, and a filename filter over them can only take specs away.
   One did: files ending `_test.clj` were skipped as a project's own tests, which made a
   namespace unmodellable for being CALLED a test. `nido.tasks.nido-test` is a task-runner
   namespace like its thirty-seven siblings, and the spec that would have modelled it was the
   one spec discovery never found."
  [^java.io.File root]
  (let [root-path (.getCanonicalPath root)]
    (->> (file-seq root)
         (filter (fn [^java.io.File f]
                   (and (.isFile f) (str/ends-with? (.getName f) ".clj"))))
         (map (fn [^java.io.File f]
                {:root root :rel-path (subs (.getCanonicalPath f) (inc (count root-path)))})))))

(defn- discover-canvas-namespaces
  "Sorted, distinct spec namespace symbols across every `*spec-dirs*` root. When no
   spec root is locatable (a fresh consumer with no specs yet, or fukan mid-rebuild)
   returns [] with a one-line stderr note, so an empty model is never silent."
  []
  (let [roots (mapcat spec-root-dirs *spec-dirs*)]
    (if (seq roots)
      (->> roots
           (mapcat (fn [{:keys [dir root]}]
                     (map (fn [{:keys [rel-path]}] (file->ns-symbol dir rel-path))
                          (discover-canvas-files-in root))))
           distinct sort vec)
      (do (binding [*out* *err*]
            (println "canvas-source: no spec root found — building an empty model."))
          []))))

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

(defn ^{:malli/schema [:=> [:cat] [:vector :any]]}
  canvas-namespaces
  "The auto-discovered canvas namespace symbols (public for inspection)."
  []
  (discover-canvas-namespaces))

(defn ^{:malli/schema [:=> [:cat] [:vector :any]]}
  require-canvas-namespaces!
  "Discover every canvas namespace and REQUIRE it (registering its vocabulary and interning
   its instance `def`s), returning the ns symbols. The native Cozo build's instance-var scan
   (`model->cozo`) reads `ns-interns`, so the namespaces must be loaded first — this is that
   load step. (References between instances are ordinary var refs the assembler resolves; there
   is no merge/cross-ref pass.)"
  []
  (let [nss (discover-canvas-namespaces)]
    (doseq [ns-sym nss] (require-canvas-namespace ns-sym))
    nss))
