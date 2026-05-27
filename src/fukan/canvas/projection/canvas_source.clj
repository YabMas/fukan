(ns fukan.canvas.projection.canvas-source
  "Canvas-source projection pipeline.

   Provides three public functions:
     build-canvas-db — require all canvas namespaces, call their build-canvas fns,
                       merge the resulting per-module Datascript dbs into one unified db.
     project         — transform the unified Datascript db into the model map shape
                       the graph viewer expects.
     build           — convenience: build-canvas-db + project in one call.

   Registry of all 62 canvas namespaces is maintained explicitly here.
   Adding a new canvas port requires adding one line to canvas-namespaces."
  (:require
   ;; Canvas port namespaces — authoritative registry (62 entries)
   ;; Note: namespace identifiers use hyphens per Clojure convention
   canvas.agent.api
   canvas.agent.edb
   canvas.agent.query
   canvas.agent.sci
   canvas.agent.system
   canvas.agent.views-loader
   canvas.constraint.ast
   canvas.constraint.builtins
   canvas.constraint.derivations
   canvas.constraint.derivations-extra
   canvas.constraint.evaluator
   canvas.constraint.phase5
   canvas.constraint.sort
   canvas.constraint.well-known
   canvas.infra.model
   canvas.infra.server
   canvas.libs.allium.parser
   canvas.libs.boundary.parser
   canvas.libs.coordinate
   canvas.model.artifact
   canvas.model.build
   canvas.model.effect
   canvas.model.expression
   canvas.model.pipeline
   canvas.model.primitives
   canvas.model.relations
   canvas.model.spec
   canvas.model.type
   canvas.model.vocabulary
   canvas.project-layer.defaults
   canvas.project-layer.registry
   canvas.target.clojure.address
   canvas.target.clojure.analyzer
   canvas.target.clojure.blueprint
   canvas.target.clojure.projector
   canvas.target.clojure.source
   canvas.target.clojure.types
   canvas.validation.phase4
   canvas.validation.rules-4a
   canvas.validation.rules-4b
   canvas.validation.rules-4c
   canvas.validation.rules-4d
   canvas.validation.rules-4e
   canvas.validation.rules-4f
   canvas.validation.rules-4g
   canvas.validation.violation
   canvas.vocabulary.allium.analyzer
   canvas.vocabulary.allium.effect-canonicalise
   canvas.vocabulary.allium.expression
   canvas.vocabulary.allium.pipeline
   canvas.vocabulary.allium.renderers
   canvas.vocabulary.allium.tags
   canvas.vocabulary.boundary.analyzer
   canvas.vocabulary.boundary.pipeline
   canvas.vocabulary.boundary.tags
   canvas.web.handler
   canvas.web.views.breadcrumb
   canvas.web.views.cytoscape
   canvas.web.views.graph
   canvas.web.views.projection
   canvas.web.views.shell
   canvas.web.views.sidebar
   ;; Infrastructure
   [clojure.string :as str]
   [datascript.core :as d]
   [fukan.canvas.core.substrate.store :as store]
   [fukan.canvas.identity :as identity]))

;; ---------------------------------------------------------------------------
;; Registry — one entry per canvas port namespace
;; ---------------------------------------------------------------------------

(def ^:private canvas-namespaces
  "Explicit registry of all canvas port namespaces. Each must export build-canvas."
  '[canvas.agent.api
    canvas.agent.edb
    canvas.agent.query
    canvas.agent.sci
    canvas.agent.system
    canvas.agent.views-loader
    canvas.constraint.ast
    canvas.constraint.builtins
    canvas.constraint.derivations
    canvas.constraint.derivations-extra
    canvas.constraint.evaluator
    canvas.constraint.phase5
    canvas.constraint.sort
    canvas.constraint.well-known
    canvas.infra.model
    canvas.infra.server
    canvas.libs.allium.parser
    canvas.libs.boundary.parser
    canvas.libs.coordinate
    canvas.model.artifact
    canvas.model.build
    canvas.model.effect
    canvas.model.expression
    canvas.model.pipeline
    canvas.model.primitives
    canvas.model.relations
    canvas.model.spec
    canvas.model.type
    canvas.model.vocabulary
    canvas.project-layer.defaults
    canvas.project-layer.registry
    canvas.target.clojure.address
    canvas.target.clojure.analyzer
    canvas.target.clojure.blueprint
    canvas.target.clojure.projector
    canvas.target.clojure.source
    canvas.target.clojure.types
    canvas.validation.phase4
    canvas.validation.rules-4a
    canvas.validation.rules-4b
    canvas.validation.rules-4c
    canvas.validation.rules-4d
    canvas.validation.rules-4e
    canvas.validation.rules-4f
    canvas.validation.rules-4g
    canvas.validation.violation
    canvas.vocabulary.allium.analyzer
    canvas.vocabulary.allium.effect-canonicalise
    canvas.vocabulary.allium.expression
    canvas.vocabulary.allium.pipeline
    canvas.vocabulary.allium.renderers
    canvas.vocabulary.allium.tags
    canvas.vocabulary.boundary.analyzer
    canvas.vocabulary.boundary.pipeline
    canvas.vocabulary.boundary.tags
    canvas.web.handler
    canvas.web.views.breadcrumb
    canvas.web.views.cytoscape
    canvas.web.views.graph
    canvas.web.views.projection
    canvas.web.views.shell
    canvas.web.views.sidebar])

;; ---------------------------------------------------------------------------
;; Merge helpers
;; ---------------------------------------------------------------------------

;; Scalar cardinality-many attributes that need to be accumulated as sets
;; during entity-map extraction (as opposed to ref-typed :module/child which
;; is handled separately in child-txs).
(def ^:private scalar-card-many-attrs
  #{:entity/tag :entity/alias :references})

(defn- db->entity-maps
  "Extract all entities from a per-module Datascript db as transactable data.

   Returns {:entity-maps [...] :child-txs [...]} where:
     - entity-maps contains one map per entity with all non-ref attributes
     - child-txs contains :module/child ref assertions using :entity/id lookup-refs

   Two-pass approach: entity-maps are transacted first (establishing identity
   via :entity/id unique attr), then child-txs are transacted to wire refs.
   This avoids Datascript lookup-ref failures when entities haven't been added yet.

   Cardinality-many scalar attributes (entity/tag, entity/alias, references) are
   accumulated as sets so that all values survive the extraction."
  [db]
  (let [eids (d/q '[:find [?e ...] :where [?e :entity/id _]] db)
        eid->uuid (into {} (map (fn [eid]
                                  [eid (ffirst (d/q '[:find ?id :in $ ?e :where [?e :entity/id ?id]] db eid))])
                                eids))
        entity-maps (mapv (fn [eid]
                            (reduce (fn [m datom]
                                      (let [attr (.-a datom)
                                            val  (.-v datom)]
                                        (cond
                                          (= attr :module/child)
                                          m ; skip ref attrs in first pass

                                          (contains? scalar-card-many-attrs attr)
                                          (update m attr (fnil conj #{}) val)

                                          :else
                                          (assoc m attr val))))
                                    {}
                                    (d/datoms db :eavt eid)))
                          eids)
        child-txs (mapcat (fn [eid]
                            (let [uuid        (get eid->uuid eid)
                                  child-datoms (filter #(= :module/child (.-a %))
                                                       (d/datoms db :eavt eid))]
                              (map (fn [datom]
                                     [:db/add
                                      [:entity/id uuid]
                                      :module/child
                                      [:entity/id (get eid->uuid (.-v datom))]])
                                   child-datoms)))
                          eids)]
    {:entity-maps entity-maps :child-txs child-txs}))

(defn- merge-dbs
  "Merge a seq of per-module Datascript dbs into one unified db.
   Entities keep their UUIDs (stable :entity/id). Cross-module :references
   relations remain as keyword values — they are not resolved to entity refs
   at merge time; the projection layer resolves them by name."
  [dbs]
  (let [extractions (map db->entity-maps dbs)
        all-entity-maps (mapcat :entity-maps extractions)
        all-child-txs   (mapcat :child-txs extractions)]
    (-> (store/create)
        (d/db-with all-entity-maps)
        (d/db-with all-child-txs))))

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
  (let [rows (d/q '[:find ?mn ?cn ?c ?role
                     :where [?m :entity/type :Module]
                            [?m :entity/name ?mn]
                            [?m :module/child ?c]
                            [?c :entity/name ?cn]
                            [(get-else $ ?c :affordance/role :none) ?role]]
                   db)]
    (->> rows
         (group-by (fn [[mn cn _ _]] [mn cn]))
         (keep (fn [[[mn cn] entries]]
                 (let [child-eids (into #{} (map #(nth % 2)) entries)
                       roles      (into #{} (map #(nth % 3)) entries)]
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
  (let [per-module-dbs (mapv (fn [ns-sym]
                               (let [build-fn (ns-resolve (the-ns ns-sym) 'build-canvas)]
                                 (when-not build-fn
                                   (throw (ex-info (str "No build-canvas fn in " ns-sym)
                                                   {:namespace ns-sym})))
                                 (build-fn)))
                             canvas-namespaces)
        unified-db (merge-dbs per-module-dbs)]
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
    :fukan.canvas.monolith/exposed-call   :primitive/operation
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
   Returns {stable-id → primitive-map}."
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
                            db))]
    (into {} (keep (fn [[uuid name role]]
                     (when-let [id (get uuid->stable-id uuid)]
                       (let [kind  (affordance-kind role)
                             prim  (cond-> {:kind  kind
                                            :id    id
                                            :label name}
                                     (get docs uuid)              (assoc :description (get docs uuid))
                                     ;; :primitive/operation requires :parameters in the Malli schema
                                     (= kind :primitive/operation) (assoc :parameters []))]
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

(defn- resolve-reference-target
  "Given a cross-module reference keyword (e.g. :model/Model), find the child
   entity of a module whose name matches the keyword's namespace, returning the
   stable string id of the target entity, or nil if not found.

   Module-qualified resolution:
     1. Take the keyword namespace as the module-name hint (e.g. \"model\").
     2. Find all Modules whose dot-separated name contains that string as a
        segment (e.g. \"model.spec\", \"model.build\" all match \"model\").
     3. Among those modules, find the child entity with the keyword's local name.
     4. Return the first match's stable id, or nil if unresolvable.

   This correctly disambiguates :modA/Foo from :modB/Foo when both modules
   export an entity named 'Foo'."
  [db uuid->stable-id ref-kw]
  (when (namespace ref-kw)
    (let [ns-str      (namespace ref-kw)
          entity-name (name ref-kw)
          ;; Find all modules whose name has ns-str as a segment
          all-modules (d/q '[:find ?m ?mn
                              :where [?m :entity/type :Module]
                                     [?m :entity/name ?mn]]
                            db)
          matching-module-eids (->> all-modules
                                    (filter (fn [[_eid mname]]
                                              (module-name-matches-ns? mname ns-str)))
                                    (map first))
          ;; Among matching modules, find a child with the given name
          result (when (seq matching-module-eids)
                   (ffirst (d/q '[:find ?cuuid
                                   :in $ [?m ...] ?n
                                   :where [?m :module/child ?c]
                                          [?c :entity/name ?n]
                                          [?c :entity/id ?cuuid]]
                                 db matching-module-eids entity-name)))]
      (when result
        (get uuid->stable-id result)))))

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
;; Public: build
;; ---------------------------------------------------------------------------

(defn build
  "Convenience: build-canvas-db + project in one call.
   Returns a model map ready for the pipeline."
  []
  (project (build-canvas-db)))
