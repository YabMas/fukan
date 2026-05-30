(ns fukan.canvas.projection.canvas-source
  "Canvas-source projection pipeline.

   Provides three public functions:
     build-canvas-db — discover all canvas namespaces on the classpath, require
                       them, call their build-canvas fns, merge the resulting
                       per-module Datascript dbs into one unified db.
     project         — transform the unified Datascript db into the model map shape
                       the graph viewer expects.
     build           — convenience: build-canvas-db + project in one call.

   Canvas namespaces are auto-discovered: any `canvas/**/*.clj` file on the
   classpath is treated as a canvas port and expected to define a
   `build-canvas` fn. Adding a new canvas port is now a single file drop;
   `(reset)` picks it up without any registry edit."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [datascript.core :as d]
   [fukan.canvas.core.classification :as classification]
   [fukan.canvas.core.substrate.store :as store]
   [fukan.canvas.identity :as identity]
   [fukan.canvas.vocab.registry :as vocab-registry]))

;; ---------------------------------------------------------------------------
;; Discovery — scan canvas/ for *.clj and derive namespace symbols
;; ---------------------------------------------------------------------------

(defn- file->ns-segment
  "Convert a single path segment from filename form to namespace form:
   strip trailing .clj if present, then turn underscores into hyphens
   (the standard Clojure file-to-ns convention)."
  [seg]
  (-> seg
      (str/replace #"\.clj$" "")
      (str/replace #"_" "-")))

(defn- file->ns-symbol
  "Convert a file path relative to the canvas root into a namespace symbol.
   Example: \"infra/server.clj\"           → 'canvas.infra.server
            \"project_layer/registry.clj\" → 'canvas.project-layer.registry
            \"validation/rules_4a.clj\"    → 'canvas.validation.rules-4a"
  [^String rel-path]
  (let [segments (str/split rel-path #"/")
        ns-segments (mapv file->ns-segment segments)]
    (symbol (str "canvas." (str/join "." ns-segments)))))

(defn- canvas-root-dirs
  "Return every `canvas/` directory accessible on the classpath as a
   java.io.File. Enumerates classpath entries via the context ClassLoader's
   `getResources`, so both the project-root `canvas/` (the spec garden) and
   any test-classpath `canvas/` (test fixtures, demo canvases) are included.

   Falls back to a relative-path `canvas/` lookup when the classpath search
   yields nothing — handy under harnesses that change cwd or override the
   loader. Returns nil when no canvas/ directory is locatable at all."
  []
  (let [cl       (.getContextClassLoader (Thread/currentThread))
        urls     (when cl (enumeration-seq (.getResources cl "canvas")))
        from-cp  (->> urls
                      (keep (fn [^java.net.URL u]
                              (try (io/as-file u)
                                   (catch Exception _ nil))))
                      (filter #(and (some? %) (.isDirectory ^java.io.File %)))
                      vec)
        from-cwd (io/file "canvas")
        roots    (cond-> from-cp
                   (and (empty? from-cp) (.isDirectory from-cwd))
                   (conj from-cwd))]
    (when (seq roots) roots)))

(defn- discover-canvas-files-in
  "Yield {:root <file> :rel-path <str>} for every non-test `*.clj` file
   under one canvas root directory. Test files (`*_test.clj`) are skipped:
   they live alongside canvas content in `test/canvas/**` and don't carry
   build-canvas fns."
  [^java.io.File root]
  (let [root-path (.getCanonicalPath root)]
    (->> (file-seq root)
         (filter (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (and (.isFile f)
                          (str/ends-with? n ".clj")
                          (not (str/ends-with? n "_test.clj"))))))
         (map (fn [^java.io.File f]
                (let [abs (.getCanonicalPath f)
                      rel (subs abs (inc (count root-path)))]
                  {:root root :rel-path rel}))))))

(defn- discover-canvas-namespaces
  "Walk every canvas/ directory on the classpath and return a sorted vector
   of distinct namespace symbols (one per `*.clj` file, excluding
   `*_test.clj`). Sorted so build order is deterministic across runs.

   Fails fast with a clear error if no canvas/ root can be located — at
   that point fukan has nothing to project and continuing would silently
   produce an empty model."
  []
  (if-let [roots (canvas-root-dirs)]
    (->> roots
         (mapcat discover-canvas-files-in)
         (map (fn [{:keys [rel-path]}] (file->ns-symbol rel-path)))
         distinct
         sort
         vec)
    (throw (ex-info "canvas-source: canvas/ root not found on classpath or working directory"
                    {:cwd (System/getProperty "user.dir")}))))

(defn- require-and-resolve-build-canvas
  "Require a canvas namespace and resolve its build-canvas var. Throws if
   the namespace fails to load or does not define build-canvas."
  [ns-sym]
  (try
    (require ns-sym)
    (catch Exception e
      (throw (ex-info (str "canvas-source: failed to load canvas namespace " ns-sym)
                      {:namespace ns-sym}
                      e))))
  (let [build-fn (ns-resolve (the-ns ns-sym) 'build-canvas)]
    (when-not build-fn
      (throw (ex-info (str "canvas-source: no build-canvas fn in " ns-sym)
                      {:namespace ns-sym})))
    build-fn))

(defn canvas-namespaces
  "Return the vector of auto-discovered canvas namespace symbols. Public so
   tests and external tooling can inspect the discovered surface."
  []
  (discover-canvas-namespaces))

;; ---------------------------------------------------------------------------
;; Merge helpers
;; ---------------------------------------------------------------------------

;; The merge is schema-driven (see db->entity-maps): ref-typed attrs are
;; translated to identity lookup-refs and cardinality-many scalars are
;; accumulated as sets, both discovered from the store schema. New substrate
;; entity types and attrs are carried automatically — no per-attr registration.

(defn- db->entity-maps
  "Extract all identity-bearing entities from a per-module Datascript db as
   transactable data — schema-driven, so ANY substrate entity type (nodes,
   shapes, tag-applications, …) is carried without per-attr registration.

   Returns {:entity-maps [...] :ref-txs [...]}:
     - entity-maps: one map per entity with its identity attr plus all scalar
       attrs (cardinality-many scalars accumulated as sets).
     - ref-txs: a [:db/add src-lookup-ref attr tgt-lookup-ref] assertion for
       every ref-typed datom, translating per-module eids to identity
       lookup-refs.

   Two-pass (entity-maps then ref-txs) so forward refs resolve and per-module
   eids — meaningless in the merged db — never leak across the boundary.
   Identity attrs, ref-typed attrs and cardinality-many attrs are all read
   from the store schema."
  [db]
  (let [schema   (:schema db)
        id-attrs (->> schema
                      (keep (fn [[a m]] (when (= :db.unique/identity (:db/unique m)) a)))
                      set)
        ref?     (fn [a] (= :db.type/ref (get-in schema [a :db/valueType])))
        many?    (fn [a] (= :db.cardinality/many (get-in schema [a :db/cardinality])))
        ident    (fn [eid]
                   (some (fn [a] (when-let [d (first (seq (d/datoms db :eavt eid a)))]
                                   [a (.-v d)]))
                         id-attrs))
        eids     (d/q '[:find [?e ...] :where [?e _ _]] db)
        eid->id  (into {} (keep (fn [e] (when-let [i (ident e)] [e i])) eids))
        entity-maps (mapv (fn [[eid [ida idv]]]
                            (reduce (fn [m datom]
                                      (let [a (.-a datom) v (.-v datom)]
                                        (cond
                                          (ref? a)  m ; refs → ref-txs
                                          (many? a) (update m a (fnil conj #{}) v)
                                          :else     (assoc m a v))))
                                    {ida idv}
                                    (d/datoms db :eavt eid)))
                          eid->id)
        ref-txs (mapcat (fn [[eid [ida idv]]]
                          (keep (fn [datom]
                                  (let [a (.-a datom) tgt (.-v datom)]
                                    (when (ref? a)
                                      (when-let [[ta tv] (eid->id tgt)]
                                        [:db/add [ida idv] a [ta tv]]))))
                                (d/datoms db :eavt eid)))
                        eid->id)]
    {:entity-maps entity-maps :ref-txs ref-txs}))

(defn- merge-dbs
  "Merge a seq of per-module Datascript dbs into one unified db. Entities keep
   their stable identities; all ref-typed attrs are translated from per-module
   eids to identity lookup-refs (two passes: identities + scalars first, then
   refs) so they resolve to the correct target in the merged db. Cross-module
   :references stay keyword-valued (resolved by name in the projection layer)."
  [dbs]
  (let [extractions (map db->entity-maps dbs)]
    (-> (store/create)
        (d/db-with (mapcat :entity-maps extractions))
        (d/db-with (mapcat :ref-txs extractions)))))

;; ---------------------------------------------------------------------------
;; Intra-module same-name detection — the name+role convention
;; ---------------------------------------------------------------------------
;;
;; A canvas module may declare multiple entities with the same :entity/name
;; PROVIDED they have distinct :affordance/role values. The canonical example
;; is the rule + invariant pair in `canvas/validation/*`: a single behavioral
;; commitment expressed from two complementary angles — a reactive `rule`
;; (role :canvas/rule) and a timeless `invariant` (role :canvas/invariant)
;; — naturally share a name.
;;
;; Reference resolution disambiguates such pairs via the (name, role) tuple,
;; where role is unambiguous from context (a `when`-trigger position resolves
;; to the rule, a `holds-that` position to the invariant, etc.).
;;
;; The warn-not-throw behavior below is the FINAL design, not transitional:
;; warnings surface authoring bugs (e.g. two `value`s with the same name and
;; identical role) without blocking startup when the colliding declarations
;; carry the intended distinct roles.

(defn- find-intra-module-collisions
  "Query the db for same-name child entities within each Module.
   Returns a seq of {:module, :name, :roles, :count} maps where roles is the
   set of distinct :affordance/role values observed for the colliding name;
   empty when no collisions.

   When :roles is a singleton, the duplicates are NOT explained by the
   name+role convention and constitute a likely authoring bug."
  [db]
  (let [rows (d/q '[:find ?mn ?cn ?c
                     :where [?m :entity/type :Module]
                            [?m :entity/name ?mn]
                            [?m :module/child ?c]
                            [?c :entity/name ?cn]]
                   db)]
    (->> rows
         (group-by (fn [[mn cn _]] [mn cn]))
         (keep (fn [[[mn cn] entries]]
                 (let [child-eids (into #{} (map #(nth % 2)) entries)
                       ;; The colliding entities' immediate kinds (the name+role
                       ;; convention's "role", generalised to each node's tag).
                       ;; Nodes with no tag-application default to :none, matching
                       ;; the prior :affordance/role get-else behaviour.
                       roles      (into #{} (map (fn [[_ _ c]]
                                                   (or (classification/direct-kind db c) :none)))
                                        entries)]
                   (when (> (count child-eids) 1)
                     {:module mn :name cn :roles roles :count (count child-eids)}))))
         (vec))))

(defn detect-intra-module-duplicates-for-test
  "Throw ExceptionInfo if any Module has two children with the same
   :entity/name. Exposed for white-box unit tests of the strict variant.

   Production canvas ingestion uses the warn-not-throw variant
   (`detect-intra-module-duplicates-warn`) because the name+role convention
   permits intentional same-name pairs with distinct roles.

   Cross-module duplicates (same name in different modules) are NOT flagged
   here; the module-qualified resolution in project-edges disambiguates them
   via the keyword namespace."
  [db]
  (let [collisions (find-intra-module-collisions db)]
    (when (seq collisions)
      (throw (ex-info "Intra-module duplicate entity names — authoring bug"
                      {:duplicates collisions}))))
  nil)

(defn- format-collision-line
  "Human-readable line for a single intra-module collision.
   Roles appear sorted by keyword name for stable output."
  [{:keys [module name roles]}]
  (let [role-list (->> roles
                       (map str)
                       sort
                       (str/join ", "))]
    (str "duplicate name " module "/" name
         " — distinct roles " role-list
         " — confirm intentional via the name+role convention")))

(defn- detect-intra-module-duplicates-warn
  "Warn to stderr for each intra-module same-name collision.

   The name+role convention permits a canvas module to declare multiple
   entities with the same :entity/name when their :affordance/role values
   differ (e.g. the rule + invariant pair in `canvas/validation/*`). The
   warning is informational: it surfaces every such collision so an author
   can confirm the distinct roles are intentional rather than accidental."
  [db]
  (let [collisions (find-intra-module-collisions db)]
    (when (seq collisions)
      (binding [*out* *err*]
        (doseq [c collisions]
          (println (str "canvas-source: " (format-collision-line c)))))))
  nil)

;; ---------------------------------------------------------------------------
;; Public: merge-for-test
;; ---------------------------------------------------------------------------

(defn merge-for-test
  "Test-accessible entry point for merge-dbs.
   Merges a seq of per-module Datascript dbs into one unified db."
  [dbs]
  (merge-dbs dbs))

(defn merge-module-dbs
  "Public entry point for merging arbitrary per-module Datascript dbs.
   Intended for demo loaders and external tooling that build their own
   set of per-module dbs outside the canvas registry.
   Merges a seq of Datascript dbs into one unified db."
  [dbs]
  (merge-dbs dbs))

;; ---------------------------------------------------------------------------
;; Public: build-canvas-db
;; ---------------------------------------------------------------------------

(defn build-canvas-db
  "Require all canvas namespaces, call their build-canvas fns, merge the
   resulting per-module Datascript dbs into one unified db. Returns a
   Datascript db.

   Fails fast if any canvas namespace fails to load (compilation error in
   a port = load error at startup).

   Intra-module same-name entities are permitted under the name+role
   convention: a module may declare multiple entities with the same
   :entity/name PROVIDED they have distinct :affordance/role values
   (the rule + invariant pair in `canvas/validation/*` is the canonical
   example). Each such collision is logged to stderr as an informational
   warning so authors can confirm the distinct roles are intentional.
   This warn-not-throw behavior is the final design.

   Cross-module duplicate names are permitted — the module-qualified
   reference resolution disambiguates them via the keyword namespace."
  []
  (let [ns-syms        (discover-canvas-namespaces)
        per-module-dbs (mapv (fn [ns-sym]
                               (let [build-fn (require-and-resolve-build-canvas ns-sym)]
                                 (build-fn)))
                             ns-syms)
        unified-db     (merge-dbs per-module-dbs)]
    (detect-intra-module-duplicates-warn unified-db)
    unified-db))

;; ---------------------------------------------------------------------------
;; Projection: canvas Datascript db → model map
;; ---------------------------------------------------------------------------

(defn- stable-module-id
  "Stable string id for a Module: delegates to identity/stable-id."
  [module-name]
  (identity/stable-id :Module module-name module-name))

(defn- stable-affordance-id
  "Stable string id for an Affordance: delegates to identity/stable-id."
  [module-name entity-name]
  (identity/stable-id :Affordance module-name entity-name))

(defn- stable-type-id
  "Stable string id for a Type: delegates to identity/stable-id."
  [module-name entity-name]
  (identity/stable-id :Type module-name entity-name))

(defn- stable-state-id
  "Stable string id for a State: delegates to identity/stable-id."
  [module-name entity-name]
  (identity/stable-id :State module-name entity-name))

(defn- affordance-kind
  "Map canvas affordance role to kernel primitive kind."
  [role]
  (case role
    :canvas/invariant                     :primitive/rule
    :canvas/rule                          :primitive/rule
    :canvas/getter                        :primitive/operation
    :canvas/checker                       :primitive/operation
    :canvas/event                         :primitive/event
    :canvas/function   :primitive/operation
    :primitive/operation))  ; default fallback

(defn- build-module-id-map
  "Build a map from Datascript entity UUID → stable string id for all entities.
   This requires knowing which module owns which entity."
  [db]
  (let [;; Modules: uuid → stable id
        module-entries (d/q '[:find ?uuid ?name
                               :where [?e :entity/type :Module]
                                      [?e :entity/id ?uuid]
                                      [?e :entity/name ?name]]
                             db)
        module-id-map  (into {} (map (fn [[uuid name]] [uuid (stable-module-id name)]) module-entries))

        ;; For owned entities: need to know their parent module
        ;; Query: for each module, get its UUID and name, and each child's UUID, type, name
        child-entries (d/q '[:find ?mod-name ?child-uuid ?child-type ?child-name
                              :where [?m :entity/type :Module]
                                     [?m :entity/name ?mod-name]
                                     [?m :module/child ?c]
                                     [?c :entity/id ?child-uuid]
                                     [?c :entity/type ?child-type]
                                     [?c :entity/name ?child-name]]
                            db)

        ;; Build stable ids for each child entity
        child-id-map (into {} (map (fn [[mod-name child-uuid child-type child-name]]
                                     [child-uuid
                                      (case child-type
                                        :Affordance (stable-affordance-id mod-name child-name)
                                        :Type       (stable-type-id mod-name child-name)
                                        :State      (stable-state-id mod-name child-name)
                                        ;; fallback
                                        (str mod-name "/" (name child-type) "/" child-name))])
                                   child-entries))]
    (merge module-id-map child-id-map)))

(defn- project-modules
  "Project all Module entities into primitive map entries.
   Returns {stable-id → primitive-map}."
  [db]
  (let [modules (d/q '[:find ?uuid ?name
                        :where [?e :entity/type :Module]
                               [?e :entity/id ?uuid]
                               [?e :entity/name ?name]]
                     db)]
    (into {} (map (fn [[_ name]]
                    (let [id (stable-module-id name)]
                      [id {:kind  :primitive/container
                           :id    id
                           :label name}]))
                  modules))))

(defn- project-affordances
  "Project all Affordance entities into primitive map entries.
   Returns {stable-id → primitive-map}.

   The primitive's :label is uniformly the affordance's :entity/name across
   all roles. For invariants this means PascalCase (e.g.
   `MajorityRequiredForLeadership`), which `addr/canonical` then kebab-cases
   into a legal Clojure symbol on both the analyzer (drift) and Layer A
   (instruct) sides. The `holds-that` prose is preserved separately via
   `:formal-expression` for consumers that want the original semantic
   clause; Layer A's `affordance-element` reads it directly from the canvas
   db instead of this primitive field, so this is informational."
  [db uuid->stable-id]
  (let [affordances-full (d/q '[:find ?uuid ?name ?role
                                 :where [?e :entity/type :Affordance]
                                        [?e :entity/id ?uuid]
                                        [?e :entity/name ?name]
                                        [?e :affordance/role ?role]]
                               db)
        docs (into {} (d/q '[:find ?uuid ?doc
                              :where [?e :entity/type :Affordance]
                                     [?e :entity/id ?uuid]
                                     [?e :affordance/doc ?doc]]
                            db))
        formal-exprs (into {} (d/q '[:find ?uuid ?fe
                                      :where [?e :entity/type :Affordance]
                                             [?e :entity/id ?uuid]
                                             [?e :affordance/formal-expression ?fe]]
                                    db))]
    (into {} (keep (fn [[uuid name role]]
                     (when-let [id (get uuid->stable-id uuid)]
                       (let [kind   (affordance-kind role)
                             fe-val (get formal-exprs uuid)
                             prim   (cond-> {:kind        kind
                                             :id          id
                                             :label       name
                                             :canvas-role role}
                                      (get docs uuid)               (assoc :description (get docs uuid))
                                      (some? fe-val)                (assoc :formal-expression fe-val)
                                      ;; Both :primitive/operation and :primitive/event require
                                      ;; :parameters in the Malli schema; canvas-source does not
                                      ;; yet project parameter shapes, so seed with [].
                                      (#{:primitive/operation
                                         :primitive/event} kind)    (assoc :parameters []))]
                         [id prim])))
                   affordances-full))))

(defn- project-types
  "Project all Type entities into primitive map entries.
   Returns {stable-id → primitive-map}."
  [db uuid->stable-id]
  (let [types (d/q '[:find ?uuid ?name
                      :where [?e :entity/type :Type]
                             [?e :entity/id ?uuid]
                             [?e :entity/name ?name]]
                   db)
        docs  (into {} (d/q '[:find ?uuid ?doc
                               :where [?e :entity/type :Type]
                                      [?e :entity/id ?uuid]
                                      [?e :type/doc ?doc]]
                             db))]
    (into {} (keep (fn [[uuid name]]
                     (when-let [id (get uuid->stable-id uuid)]
                       [id (cond-> {:kind  :primitive/container
                                    :id    id
                                    :label name}
                             (get docs uuid) (assoc :description (get docs uuid)))]))
                   types))))

(defn- project-states
  "Project all State entities into primitive map entries.
   Returns {stable-id → primitive-map}."
  [db uuid->stable-id]
  (let [states (d/q '[:find ?uuid ?name
                       :where [?e :entity/type :State]
                              [?e :entity/id ?uuid]
                              [?e :entity/name ?name]]
                    db)]
    (into {} (keep (fn [[uuid name]]
                     (when-let [id (get uuid->stable-id uuid)]
                       [id {:kind  :primitive/container
                            :id    id
                            :label name}]))
                   states))))

(defn- add-children
  "Enrich Module primitives with a :children set of their children's stable ids.
   Also add :parent to each child primitive pointing to its module's stable id."
  [primitives db uuid->stable-id]
  (let [;; Query module→children relationships
        parent-child-pairs (d/q '[:find ?mod-uuid ?child-uuid
                                   :where [?m :entity/type :Module]
                                          [?m :entity/id ?mod-uuid]
                                          [?m :module/child ?c]
                                          [?c :entity/id ?child-uuid]]
                                 db)
        ;; Build module-id → #{child-ids} map
        children-by-module (reduce (fn [acc [mod-uuid child-uuid]]
                                     (let [mod-id   (get uuid->stable-id mod-uuid)
                                           child-id (get uuid->stable-id child-uuid)]
                                       (if (and mod-id child-id)
                                         (update acc mod-id (fnil conj #{}) child-id)
                                         acc)))
                                   {}
                                   parent-child-pairs)
        ;; Build child→module-id map for :parent attr
        parent-by-child (into {} (for [[mod-id child-ids] children-by-module
                                       child-id child-ids]
                                   [child-id mod-id]))
        ;; Add :children to each Module
        prims-with-children (reduce (fn [prims [mod-id child-ids]]
                                      (if (contains? prims mod-id)
                                        (update-in prims [mod-id :children] (fnil into #{}) child-ids)
                                        prims))
                                    primitives
                                    children-by-module)]
    ;; Add :parent to each child primitive
    (reduce (fn [prims [child-id mod-id]]
              (if (contains? prims child-id)
                (assoc-in prims [child-id :parent] mod-id)
                prims))
            prims-with-children
            parent-by-child)))

(defn- module-name-matches-ns?
  "Return true if any dot-separated segment of module-name equals ns-str.
   e.g. (module-name-matches-ns? \"model.spec\" \"model\") => true
        (module-name-matches-ns? \"constraint.ast\" \"ast\")  => true
        (module-name-matches-ns? \"model.spec\" \"spec\")  => true

   Also matches underscore↔hyphen variants so that the canvas reference
   keyword :views_loader/LoadReport finds the module named agent.views_loader."
  [module-name ns-str]
  (let [segments     (str/split module-name #"\.")
        ns-str-under (str/replace ns-str #"-" "_")
        ns-str-hyph  (str/replace ns-str #"_" "-")]
    (some (fn [seg]
            (or (= seg ns-str)
                (= seg ns-str-under)
                (= seg ns-str-hyph)))
          segments)))

(defn resolve-reference-uuid
  "Given a cross-module reference keyword (e.g. :model/Model), find the child
   entity of a module whose name matches the keyword's namespace, returning the
   :entity/id UUID of the target entity, or nil if not found.

   Two-step module match:
     1. EXACT match: a module whose :entity/name equals the keyword's namespace
        string (e.g. :mod.sub.module/Foo → module \"mod.sub.module\"). This
        lets authors pin a ref unambiguously when segments collide
        (\"accounts.users\" vs \"users.accounts\").
     2. SEGMENT match (fallback): the keyword namespace appears as a
        dot-separated segment of a module name (e.g. :model/Model matches
        modules \"model.spec\", \"model.build\", etc.). Convenient when
        segments are unique across the canvas.

   Within the matched module(s), find the child entity whose :entity/name
   equals the keyword's local name and return its UUID. Both forms are
   first-class; the resolver tries exact first so a fully-qualified author
   intent never loses to an accidental segment collision.

   Exposed as a public helper so trust-tier integrity checks can ask
   'does this reference resolve?' without needing a uuid→stable-id map."
  [db ref-kw]
  (when (namespace ref-kw)
    (let [ns-str      (namespace ref-kw)
          entity-name (name ref-kw)
          all-modules (d/q '[:find ?m ?mn
                              :where [?m :entity/type :Module]
                                     [?m :entity/name ?mn]]
                            db)
          exact-module-eids (->> all-modules
                                 (filter (fn [[_eid mname]] (= mname ns-str)))
                                 (map first))
          matching-module-eids
          (if (seq exact-module-eids)
            exact-module-eids
            (->> all-modules
                 (filter (fn [[_eid mname]]
                           (module-name-matches-ns? mname ns-str)))
                 (map first)))]
      (when (seq matching-module-eids)
        (ffirst (d/q '[:find ?cuuid
                       :in $ [?m ...] ?n
                       :where [?m :module/child ?c]
                              [?c :entity/name ?n]
                              [?c :entity/id ?cuuid]]
                     db matching-module-eids entity-name))))))

(defn resolve-reference-target
  "Given a cross-module reference keyword (e.g. :model/Model), find the child
   entity of a module whose name matches the keyword's namespace, returning the
   stable string id of the target entity, or nil if not found.

   See `resolve-reference-uuid` for the resolution algorithm."
  [db uuid->stable-id ref-kw]
  (when-let [uuid (resolve-reference-uuid db ref-kw)]
    (get uuid->stable-id uuid)))

(defn- eid->stable-id
  "Resolve a Datascript integer eid to its stable string id via :entity/id UUID lookup."
  [db uuid->stable-id eid]
  (when-let [uuid (ffirst (d/q '[:find ?id :in $ ?e :where [?e :entity/id ?id]] db eid))]
    (get uuid->stable-id uuid)))

(defn- project-edges
  "Project canvas Relations into model edge maps.
   Handles :references and other relation attributes.
   :module/child is NOT projected as edges — it becomes :parent/:children instead.
   Edges with unresolvable targets are dropped.

   Edge endpoints conform to the kernel Endpoint schema:
     {:case :endpoint/primitive :id <stable-string-id>}"
  [db uuid->stable-id]
  ;; :references edges (keyword target — cross-module ref)
  (let [ref-datoms (d/datoms db :aevt :references)]
    (keep (fn [datom]
            (let [from-eid (.-e datom)
                  ref-kw   (.-v datom)
                  from-id  (eid->stable-id db uuid->stable-id from-eid)
                  to-id    (when (keyword? ref-kw)
                              (resolve-reference-target db uuid->stable-id ref-kw))]
              (when (and from-id to-id)
                {:from {:case :endpoint/primitive :id from-id}
                 :to   {:case :endpoint/primitive :id to-id}
                 :kind :relation/uses})))
          ref-datoms)))

(defn- project-tag-apps
  "Project canvas :entity/tag values into model tag-applications.
   Tag-applications conform to the TagApplication schema:
     {:tag {:namespace :string :name :string}
      :target {:case :target/primitive :id :string}
      :payload {}}"
  [db uuid->stable-id]
  (let [tag-datoms (d/datoms db :aevt :entity/tag)]
    (keep (fn [datom]
            (let [eid (.-e datom)
                  tag (.-v datom)
                  id  (eid->stable-id db uuid->stable-id eid)]
              (when id
                {:tag     {:namespace (or (namespace tag) "canvas")
                           :name      (name tag)}
                 :target  {:case :target/primitive :id id}
                 :payload {}})))
          tag-datoms)))

;; ---------------------------------------------------------------------------
;; Public: project
;; ---------------------------------------------------------------------------

(defn project
  "Project a unified canvas Datascript db into the model map shape that
   the graph viewer expects. Returns a map conforming to build/Model:
     {:primitives, :edges, :tag-defs, :tag-apps, :predicates, :renderers, :artifacts}

   Uses stable string ids (module-name / module-name/entity-name / etc.)
   so graph node ids survive refresh cycles."
  [db]
  (let [uuid->stable-id  (build-module-id-map db)
        modules          (project-modules db)
        affordances      (project-affordances db uuid->stable-id)
        types            (project-types db uuid->stable-id)
        states           (project-states db uuid->stable-id)
        primitives-raw   (merge modules affordances types states)
        primitives       (add-children primitives-raw db uuid->stable-id)
        raw-edges        (project-edges db uuid->stable-id)
        edges            (vec (map-indexed (fn [idx e] (assoc e :id (str "e" idx))) raw-edges))
        tag-apps         (vec (project-tag-apps db uuid->stable-id))]
    {:primitives primitives
     :edges      edges
     :tag-defs   []
     :tag-apps   tag-apps
     :predicates []
     :renderers  []
     :artifacts  {}}))

;; ---------------------------------------------------------------------------
;; Phase-6 content read back out of the db (Step B): the db is the source of
;; artifacts + projects edges; these reconstruct the model-map view of them.
;; Inverses of store/artifact->datoms and store/edge->datoms.
;; ---------------------------------------------------------------------------

(defn stable->uuid-map
  "Inverse of build-module-id-map: stable string id → :entity/id UUID. Used to
   resolve projects-edge :from endpoints (stable-ids) to primitive entities
   when the analyzer transacts Phase-6 edges into the db."
  [db]
  (set/map-invert (build-module-id-map db)))

(defn db->artifacts
  "Reconstruct the model map's :artifacts {identity-tuple → artifact-map} from
   :artifact/* entities in the db. Inverse of store/artifact->datoms. Fields
   are read back from their pr-str form; their iteration order is irrelevant —
   the only consumer (shape-drift) treats them as a name→type map."
  [db]
  (let [aeids (d/q '[:find [?a ...] :where [?a :artifact/id _]] db)]
    (into {}
          (map (fn [aeid]
                 (let [e        (d/entity db aeid)
                       sub-case (:artifact/sub-case e)
                       lang     (:artifact/language e)
                       qn       (:artifact/qualified-name e)
                       sf       (:artifact/source-file e)
                       sl       (:artifact/source-line e)
                       fields   (:artifact/fields e)
                       art {:case     (:artifact/case e)
                            :language lang
                            :sub      (cond-> {:case sub-case :qualified-name qn}
                                        (some? (:artifact/public e))
                                        (assoc :public? (:artifact/public e))
                                        sf
                                        (assoc :source-location
                                               (cond-> {:file sf} sl (assoc :line sl)))
                                        (seq fields)
                                        (assoc :fields (mapv edn/read-string fields)))}]
                   [[sub-case lang qn] art])))
          aeids)))

(defn db->projects-edges
  "Reconstruct the model map's :relation/projects edges from :edge/* entities
   in the db. Inverse of store/edge->datoms."
  [db]
  (let [uuid->stable (build-module-id-map db)
        eeids (d/q '[:find [?e ...] :where [?e :edge/kind :relation/projects]] db)]
    (mapv (fn [eeid]
            (let [e           (d/entity db eeid)
                  from-eid    (:db/id (:edge/from e))
                  from-stable (eid->stable-id db uuid->stable from-eid)
                  art         (:edge/to e)
                  art-tuple   [(:artifact/sub-case art)
                               (:artifact/language art)
                               (:artifact/qualified-name art)]]
              {:kind            :relation/projects
               :from            {:case :endpoint/primitive :id from-stable}
               :to              {:case :endpoint/artifact :id art-tuple}
               :projection-kind (:edge/projection-kind e)
               :validity        (:edge/validity e)}))
          eeids)))

;; ---------------------------------------------------------------------------
;; Substrate enrichment (Step C): make the unified db directly queryable by
;; agent L0 — stamp stable-ids and resolve cross-module refs into :uses edges.
;; ---------------------------------------------------------------------------

(defn- stable-id-txs
  "Stamp :entity/stable-id onto every entity, computed the same way the
   projection labels graph nodes (via build-module-id-map)."
  [db]
  (mapv (fn [[uuid sid]] [:db/add [:entity/id uuid] :entity/stable-id sid])
        (build-module-id-map db)))

(defn- uses-txs
  "Resolve each :references keyword datom to a :uses ref-datom toward the
   target entity. Unresolvable references are dropped (same policy as the
   map-side project-edges)."
  [db]
  (->> (d/datoms db :aevt :references)
       (keep (fn [datom]
               (let [from-eid (.-e datom)
                     ref-kw   (.-v datom)]
                 (when (keyword? ref-kw)
                   (when-let [to-uuid (resolve-reference-uuid db ref-kw)]
                     [:db/add from-eid :uses [:entity/id to-uuid]])))))
       vec))

(defn- tagdef-txs
  "Datoms projecting the tag-definition registry into the db as :tagdef/*
   entities, making the vocabulary queryable on the substrate surface."
  []
  (mapv (fn [{:keys [tag family payload doc]}]
          (cond-> {:tagdef/tag tag}
            payload (assoc :tagdef/payload payload)
            family  (assoc :tagdef/family family)
            doc     (assoc :tagdef/doc doc)))
        (vocab-registry/all)))

(defn enrich-substrate
  "Make the unified canvas db a complete, directly-queryable substrate:
   stamp :entity/stable-id on every entity, resolve cross-module :references
   into :uses ref-datoms, and project the tag-definition registry as :tagdef/*
   entities. Additive — :references keywords remain for the map-side projection."
  [db]
  (-> db
      (d/db-with (stable-id-txs db))
      (d/db-with (uses-txs db))
      (d/db-with (tagdef-txs))))

;; ---------------------------------------------------------------------------
;; Public: build
;; ---------------------------------------------------------------------------

(defn build-substrate
  "build-canvas-db + enrich-substrate: the enriched, directly-queryable db the
   pipeline retains and agent L0 queries."
  []
  (enrich-substrate (build-canvas-db)))

(defn build
  "Convenience: build-canvas-db + project in one call.
   Returns a model map ready for the pipeline."
  []
  (project (build-canvas-db)))
