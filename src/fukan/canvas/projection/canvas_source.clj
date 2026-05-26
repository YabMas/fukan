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
   [datascript.core :as d]
   [fukan.canvas.core.substrate.store :as store]))

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

(defn- db->entity-maps
  "Extract all entities from a per-module Datascript db as transactable data.

   Returns {:entity-maps [...] :child-txs [...]} where:
     - entity-maps contains one map per entity with all non-ref attributes
     - child-txs contains :module/child ref assertions using :entity/id lookup-refs

   Two-pass approach: entity-maps are transacted first (establishing identity
   via :entity/id unique attr), then child-txs are transacted to wire refs.
   This avoids Datascript lookup-ref failures when entities haven't been added yet."
  [db]
  (let [eids (d/q '[:find [?e ...] :where [?e :entity/id _]] db)
        eid->uuid (into {} (map (fn [eid]
                                  [eid (ffirst (d/q '[:find ?id :in $ ?e :where [?e :entity/id ?id]] db eid))])
                                eids))
        entity-maps (mapv (fn [eid]
                            (reduce (fn [m datom]
                                      (let [attr (.-a datom)
                                            val  (.-v datom)]
                                        (if (= attr :module/child)
                                          m ; skip ref attrs in first pass
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
;; Duplicate-name detection
;; ---------------------------------------------------------------------------

(defn- detect-duplicate-names
  "Detect entities (any type) across modules that share the same :entity/name.
   Shared names matter because the projection's cross-module reference
   resolution is by name; collisions would route references incorrectly.

   Behavior:
     - If any duplicates are found, logs a warning to stderr.
     - Does NOT throw — fukan's canvas corpus has expected cross-module name
       duplicates (e.g. 'Model' may appear across multiple module specs).
   Returns a seq of duplicate name strings (empty when none)."
  [db]
  (let [name-counts (->> (d/q '[:find ?n (count ?e)
                                 :where [?e :entity/name ?n]]
                               db)
                         (filter #(> (second %) 1))
                         (map first)
                         vec)]
    (when (seq name-counts)
      (binding [*out* *err*]
        (println "canvas-source: duplicate entity names across canvas modules:"
                 (vec name-counts)
                 "— first-match resolution will be used for cross-module references.")))
    name-counts))

;; ---------------------------------------------------------------------------
;; Public: build-canvas-db
;; ---------------------------------------------------------------------------

(defn build-canvas-db
  "Require all canvas namespaces, call their build-canvas fns, merge the
   resulting per-module Datascript dbs into one unified db. Returns a
   Datascript db.

   Fails fast if any canvas namespace fails to load (compilation error in
   a port = load error at startup).

   Logs a warning (but does not throw) if duplicate entity names are found
   across modules — cross-module reference resolution uses first-match."
  []
  (let [per-module-dbs (mapv (fn [ns-sym]
                               (let [build-fn (ns-resolve (the-ns ns-sym) 'build-canvas)]
                                 (when-not build-fn
                                   (throw (ex-info (str "No build-canvas fn in " ns-sym)
                                                   {:namespace ns-sym})))
                                 (build-fn)))
                             canvas-namespaces)
        unified-db (merge-dbs per-module-dbs)]
    (detect-duplicate-names unified-db)
    unified-db))

;; ---------------------------------------------------------------------------
;; Projection: canvas Datascript db → model map
;; ---------------------------------------------------------------------------

(defn- stable-module-id
  "Stable string id for a Module: its name (e.g. 'infra.server')."
  [module-name]
  module-name)

(defn- stable-affordance-id
  "Stable string id for an Affordance: 'module-name/entity-name'."
  [module-name entity-name]
  (str module-name "/" entity-name))

(defn- stable-type-id
  "Stable string id for a Type: 'module-name/type/entity-name'."
  [module-name entity-name]
  (str module-name "/type/" entity-name))

(defn- stable-state-id
  "Stable string id for a State: 'module-name/state/entity-name'."
  [module-name entity-name]
  (str module-name "/state/" entity-name))

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

(defn- resolve-reference-target
  "Given a cross-module reference keyword (e.g. :model/Model), find the entity
   in the unified db whose :entity/name matches the keyword's local name.
   Returns the stable string id of the target entity, or nil if not found."
  [db uuid->stable-id ref-kw]
  (let [target-name (name ref-kw)
        ;; Find the first entity with this name
        result (ffirst (d/q '[:find ?uuid
                               :in $ ?target-name
                               :where [?e :entity/name ?target-name]
                                      [?e :entity/id ?uuid]]
                             db target-name))]
    (when result
      (get uuid->stable-id result))))

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
