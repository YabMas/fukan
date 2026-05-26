(ns fukan.canvas.identity
  "Stable-id contract for canvas graph nodes.

   Exposes three public functions:

     stable-id   — pure fn; given entity kind, module name, entity name →
                   canonical stable string id. Used by the projection layer
                   to label graph nodes.

     resolve-id  — pure fn; given a canvas db and an id string (canonical OR
                   alias), returns the canonical stable id, or nil if unknown.
                   Does not throw.

     alias       — within-module side-effecting form; declares that an old stable
                   id maps to a current entity (looked up by name in the enclosing
                   module). Adds the old id to the entity's :entity/alias set.

   Stable-id format (canonical):
     Module:      \"<module-name>\"              e.g. \"infra.server\"
     Affordance:  \"<module-name>/<name>\"       e.g. \"infra.server/start_server\"
     State:       \"<module-name>/state/<name>\" e.g. \"infra.server/state/running\"
     Type:        \"<module-name>/type/<name>\"  e.g. \"infra.server/type/ServerOpts\""
  (:refer-clojure :exclude [alias])
  (:require [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]))

;; ---------------------------------------------------------------------------
;; stable-id — pure function
;; ---------------------------------------------------------------------------

(defn stable-id
  "Return the canonical stable string id for an entity.

   Arguments:
     entity-type  — one of :Module :Affordance :State :Type
     module-name  — the dot-separated module name string (e.g. \"infra.server\")
     entity-name  — the entity's name string (e.g. \"start_server\")

   For :Module, module-name and entity-name must both equal the module name
   (entity-name is ignored — the id is just the module name).

   Returns a string id per the canonical format."
  [entity-type module-name entity-name]
  (case entity-type
    :Module     module-name
    :Affordance (str module-name "/" entity-name)
    :State      (str module-name "/state/" entity-name)
    :Type       (str module-name "/type/" entity-name)
    ;; fallback for any future primitive kinds
    (str module-name "/" (name entity-type) "/" entity-name)))

;; ---------------------------------------------------------------------------
;; Internal: build the id-index from a canvas db
;; ---------------------------------------------------------------------------

(defn- build-id-index
  "Scan the canvas db and return a map {id-string → canonical-id-string} that
   covers both canonical ids and alias ids.

   Canonical entries: each entity maps its own canonical id to itself.
   Alias entries:     each :entity/alias datom maps the old id string to the
                      canonical id of the entity it is attached to.

   Builds the canonical id via the same logic as stable-id, by querying each
   entity's type, name, and parent module."
  [db]
  (let [;; Module canonical ids: uuid → stable-id
        modules (d/q '[:find ?uuid ?name
                        :where [?e :entity/type :Module]
                               [?e :entity/id ?uuid]
                               [?e :entity/name ?name]]
                     db)
        module-id-map (into {} (map (fn [[uuid mname]]
                                      [uuid (stable-id :Module mname mname)])
                                    modules))

        ;; Child canonical ids: uuid → stable-id (needs parent module name)
        children (d/q '[:find ?child-uuid ?child-type ?child-name ?mod-name
                         :where [?m :entity/type :Module]
                                [?m :entity/name ?mod-name]
                                [?m :module/child ?c]
                                [?c :entity/id ?child-uuid]
                                [?c :entity/type ?child-type]
                                [?c :entity/name ?child-name]]
                      db)
        child-id-map (into {} (map (fn [[uuid child-type child-name mod-name]]
                                     [uuid (stable-id child-type mod-name child-name)])
                                   children))

        ;; Merged: all entity-uuid → canonical-id
        uuid->canonical (merge module-id-map child-id-map)

        ;; Forward index: canonical-id → canonical-id (identity)
        canonical-index (into {} (map (fn [[_uuid canonical]] [canonical canonical])
                                      uuid->canonical))

        ;; Alias index: old-id-string → canonical-id of the entity carrying it
        ;; Query all :entity/alias datoms (Datascript stores them as EAV)
        alias-datoms (d/datoms db :aevt :entity/alias)
        alias-index  (into {} (keep (fn [datom]
                                      (let [eid     (.-e datom)
                                            old-id  (.-v datom)
                                            ;; Resolve eid to uuid
                                            uuid    (ffirst (d/q '[:find ?id
                                                                    :in $ ?e
                                                                    :where [?e :entity/id ?id]]
                                                                  db eid))]
                                        (when-let [canonical (get uuid->canonical uuid)]
                                          [old-id canonical])))
                                    alias-datoms))]
    (merge canonical-index alias-index)))

;; ---------------------------------------------------------------------------
;; resolve-id — pure function (given a db)
;; ---------------------------------------------------------------------------

(defn resolve-id
  "Resolve a stable id string (canonical or alias) to its current canonical id.

   Takes a canvas Datascript db and an id string.
   Returns the canonical stable id string, or nil if the id is unknown.
   Never throws."
  [db id-str]
  (let [index (build-id-index db)]
    (get index id-str)))

;; ---------------------------------------------------------------------------
;; stable-id-for-eid — pure function (given a db and a Datascript eid)
;; ---------------------------------------------------------------------------

(defn stable-id-for-eid
  "Return the canonical stable id string for the entity identified by the
   given Datascript integer eid.

   Returns nil if the eid is not an integer, has no :entity/id attribute,
   or does not appear in the id index (e.g. a synthetic relation target)."
  [db eid]
  (when (integer? eid)
    (let [mod-rows   (d/q '[:find ?name
                             :in $ ?e
                             :where [?e :entity/type :Module]
                                    [?e :entity/name ?name]]
                           db eid)
          child-rows (d/q '[:find ?ct ?cn ?mn
                             :in $ ?e
                             :where [?e :entity/type ?ct]
                                    [?e :entity/name ?cn]
                                    [?m :module/child ?e]
                                    [?m :entity/name ?mn]]
                           db eid)]
      (cond
        (seq mod-rows)   (stable-id :Module (ffirst mod-rows) (ffirst mod-rows))
        (seq child-rows) (let [[ct cn mn] (first child-rows)]
                           (stable-id ct mn cn))
        :else            nil))))

;; ---------------------------------------------------------------------------
;; alias — within-module side-effecting form
;; ---------------------------------------------------------------------------

(defn- find-entity-in-module
  "Find the Datascript eid of the entity with the given name in module-id."
  [db module-id entity-name]
  (ffirst (d/q '[:find ?c
                  :in $ ?mid ?n
                  :where [?m :entity/id ?mid]
                         [?m :module/child ?c]
                         [?c :entity/name ?n]]
               db module-id entity-name)))

(defn alias
  "Inside `within-module`, declare that an old stable id string maps to the
   entity with the given name in the enclosing module.

   Arguments:
     old-id              — the old canonical stable id string (e.g. \"infra.server/start\")
     current-entity-name — the current entity NAME (not its full id) in the
                           enclosing module (e.g. \"start_server\")

   Adds old-id to the entity's :entity/alias set (cardinality-many).
   If there is no enclosing module, or the entity name is not found, this
   is a no-op (silent — aliases are best-effort historical annotations)."
  [old-id current-entity-name]
  (when h/*enclosing-module*
    (let [db   @h/*store*
          eid  (find-entity-in-module db h/*enclosing-module* current-entity-name)]
      (when eid
        (swap! h/*store*
               #(d/db-with % [[:db/add eid :entity/alias old-id]]))))))
