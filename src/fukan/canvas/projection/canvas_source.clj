(ns fukan.canvas.projection.canvas-source
  "Canvas ingestion: discover the defstructure-based canvas specs on the
   classpath, call each spec's `build-canvas` (which returns a structure
   substrate db), and merge them into one structure db — which IS the model
   (design decision (ii): the structure substrate is the model; there is no
   model-map projection and no Phase-6 analyzer here anymore).

   Canvas namespaces are auto-discovered: any `canvas/**/*.clj` file on the
   classpath is a canvas port expected to define a `build-canvas` fn returning a
   `fukan.canvas.core.structure` db. Adding a port is a single file drop."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datascript.core :as d]
   [fukan.canvas.core.structure :as structure]))

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

(defn- load-and-resolve-build-canvas
  "Require a canvas namespace (throwing on a load failure) and return its
   build-canvas var, or nil when it defines none. A canvas spec without
   build-canvas is a VOCAB-ONLY spec — it `defstructure`s a vocabulary for other
   specs to author against, contributing no instances itself (mirroring the demos'
   vocab-vs-model split). Requiring it still registers its structures."
  [ns-sym]
  (try (require ns-sym)
       (catch Exception e
         (throw (ex-info (str "canvas-source: failed to load canvas namespace " ns-sym)
                         {:namespace ns-sym} e))))
  (ns-resolve (the-ns ns-sym) 'build-canvas))

(defn canvas-namespaces
  "The auto-discovered canvas namespace symbols (public for inspection)."
  []
  (discover-canvas-namespaces))

;; ---------------------------------------------------------------------------
;; Merge — combine per-spec structure dbs into one (schema-driven)
;; ---------------------------------------------------------------------------

(defn- db->entity-maps
  "Extract identity-bearing entities from one structure db as transactable data
   — schema-driven, so any structure entity (nodes, reified relations) is carried
   without per-attr knowledge. Returns {:entity-maps [...] :ref-txs [...]}: scalar
   attrs as maps (cardinality-many accumulated as sets), ref-typed datoms as
   [:db/add src-lookup-ref attr tgt-lookup-ref] over identity lookup-refs (so
   per-db eids never leak across the merge boundary)."
  [db]
  (let [schema   (:schema db)
        id-attrs (->> schema (keep (fn [[a m]] (when (= :db.unique/identity (:db/unique m)) a))) set)
        ref?     (fn [a] (= :db.type/ref (get-in schema [a :db/valueType])))
        many?    (fn [a] (= :db.cardinality/many (get-in schema [a :db/cardinality])))
        ident    (fn [eid] (some (fn [a] (when-let [d (first (seq (d/datoms db :eavt eid a)))]
                                           [a (.-v d)]))
                                 id-attrs))
        eids     (d/q '[:find [?e ...] :where [?e _ _]] db)
        eid->id  (into {} (keep (fn [e] (when-let [i (ident e)] [e i])) eids))]
    {:entity-maps (mapv (fn [[eid [ida idv]]]
                          (reduce (fn [m datom]
                                    (let [a (.-a datom) v (.-v datom)]
                                      (cond (ref? a)  m
                                            (many? a) (update m a (fnil conj #{}) v)
                                            :else     (assoc m a v))))
                                  {ida idv}
                                  (d/datoms db :eavt eid)))
                        eid->id)
     :ref-txs    (mapcat (fn [[eid [ida idv]]]
                           (keep (fn [datom]
                                   (let [a (.-a datom) tgt (.-v datom)]
                                     (when (ref? a)
                                       (when-let [[ta tv] (eid->id tgt)]
                                         [:db/add [ida idv] a [ta tv]]))))
                                 (d/datoms db :eavt eid)))
                         eid->id)}))

(defn merge-dbs
  "Merge a seq of per-spec structure dbs into one structure db. Entities keep
   their stable identities; ref-typed attrs are translated to identity
   lookup-refs (two passes: scalars first, then refs) so they resolve in the
   merged db."
  [dbs]
  (let [extractions (map db->entity-maps dbs)]
    (-> (structure/create)
        (d/db-with (mapcat :entity-maps extractions))
        (d/db-with (mapcat :ref-txs extractions)))))

;; ---------------------------------------------------------------------------
;; Cross-module references — resolve deferred `:rel/to-ref` post-merge
;; ---------------------------------------------------------------------------

(defn- resolve-ref
  "Resolve a cross-module reference vector against the merged db, returning the
   target eid (or nil): `[module]` → the :Module node of that name; `[module name]`
   → that module's child of that name."
  [db ref]
  (case (count ref)
    1 (ffirst (d/q '[:find ?m :in $ ?mn
                     :where [?m :entity/name ?mn] [?m :structure/of :Module]]
                   db (first ref)))
    2 (ffirst (d/q '[:find ?c :in $ ?mn ?cn
                     :where [?m :entity/name ?mn] [?m :structure/of :Module]
                            [?m :module/child ?c] [?c :entity/name ?cn]]
                   db (first ref) (second ref)))
    nil))

(defn resolve-cross-refs
  "Resolve every deferred cross-module reference (`:rel/to-ref`, no `:rel/to`) in
   the merged db to its actual target node. Throws on an unresolved reference — the
   cross-module analogue of the local 'not an entity in the module' error."
  [db]
  (reduce (fn [db [r ref]]
            (if-let [target (resolve-ref db ref)]
              (d/db-with db [[:db/add r :rel/to target]])
              (throw (ex-info (str "canvas-source: unresolved cross-module reference "
                                   (pr-str ref) " — no such module/node in the model")
                              {:ref ref :rel r}))))
          db
          (d/q '[:find ?r ?ref :where [?r :rel/to-ref ?ref] (not [?r :rel/to _])] db)))

;; ---------------------------------------------------------------------------
;; Build — the model
;; ---------------------------------------------------------------------------

(defn build
  "Discover every canvas spec, load it (registering its vocabulary), call each
   model spec's build-canvas (→ a structure db), merge them, and resolve deferred
   cross-module references. Vocab-only specs (no build-canvas) are loaded but
   contribute no instances. This db is the model."
  []
  (->> (discover-canvas-namespaces)
       (keep (fn [ns-sym] (when-let [bc (load-and-resolve-build-canvas ns-sym)] (bc))))
       merge-dbs
       resolve-cross-refs))
