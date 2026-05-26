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
