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
   [clojure.string :as str]
   [datascript.core :as d]
   [fukan.canvas.core.assemble :as assemble]
   [fukan.canvas.core.substrate :as substrate]))

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
   reads `ns-interns`, so the namespaces must be loaded first — this is that load step,
   factored out of the (datascript) `build` so the cozo build can share it."
  []
  (let [nss (discover-canvas-namespaces)]
    (doseq [ns-sym nss] (require-canvas-namespace ns-sym))
    nss))

;; ---------------------------------------------------------------------------
;; Union — fold the extractor's code db onto the assembled design db (schema-driven)
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

(defn union-dbs
  "Union a seq of structure dbs into one. Entities keep their stable identities
   (`:entity/id` / `:rel/id`); ref-typed attrs are translated to identity lookup-refs
   (two passes: scalars first, then refs) so they resolve in the unioned db. Used to
   fold an extractor's code db onto the assembled design db (both carry globally
   unique ids)."
  [dbs]
  (let [extractions (map db->entity-maps dbs)]
    (-> (substrate/create)
        (d/db-with (mapcat :entity-maps extractions))
        (d/db-with (mapcat :ref-txs extractions)))))

;; ---------------------------------------------------------------------------
;; Build — the model
;; ---------------------------------------------------------------------------

(defn build
  "Discover every canvas spec, require it (registering its vocabulary and interning
   its instance `def`s), and assemble all those instance-vars into one structure db.
   References between instances are ordinary var references resolved by the assembler
   — there is no separate merge/cross-ref pass. This db is the model."
  []
  (let [nss (discover-canvas-namespaces)]
    (doseq [ns-sym nss] (require-canvas-namespace ns-sym))
    (assemble/assemble nss)))
