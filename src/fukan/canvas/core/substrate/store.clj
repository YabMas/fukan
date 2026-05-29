(ns fukan.canvas.core.substrate.store
  (:require [datascript.core :as d]
            [fukan.canvas.core.shape :as shape]
            [fukan.canvas.core.substrate :as sub]))

(def ^:private schema
  {:entity/id           {:db/unique :db.unique/identity}
   :entity/type         {:db/index true}
   :entity/name         {:db/index true}
   ;; Canonical stable-id (module-name / name-qualified) stamped onto every
   ;; entity so agent L0 d/q can return the cross-fn addressing currency.
   ;; Indexed, NOT unique: name+role rule/invariant pairs share a stable-id.
   :entity/stable-id    {:db/index true}
   :module/child        {:db/cardinality :db.cardinality/many
                         :db/valueType :db.type/ref}
   :entity/tag          {:db/cardinality :db.cardinality/many}
   :entity/alias        {:db/cardinality :db.cardinality/many}
   :references          {:db/cardinality :db.cardinality/many}
   ;; Resolved cross-module dependency edge — the ref-datom form of
   ;; :references (which stays as keyword values for the map-side projection).
   :uses                {:db/cardinality :db.cardinality/many
                         :db/valueType   :db.type/ref}
   :affordance/doc          {:db/index true}
   :affordance/input-types  {:db/cardinality :db.cardinality/many}
   :affordance/output-types {:db/cardinality :db.cardinality/many}
   :type/doc                {:db/index true}
   :type/field-types        {:db/cardinality :db.cardinality/many}
   :type/fields             {:db/cardinality :db.cardinality/many}
   :type/field-shapes       {:db/cardinality :db.cardinality/many}
   :affordance/returns-label {:db/index true}
   :triggers                {:db/cardinality :db.cardinality/many
                              :db/valueType   :db.type/ref}
   :emits                   {:db/cardinality :db.cardinality/many
                              :db/valueType   :db.type/ref}

   ;; ── Artifacts (Step B) ──────────────────────────────────────────────
   ;; Phase-6 Code.* artifacts as db entities. Identity is the
   ;; (sub-case, language, qualified-name) tuple, encoded as a unique
   ;; :artifact/id string so re-transaction upserts. Distinct from
   ;; primitives' :entity/id UUIDs.
   :artifact/id             {:db/unique :db.unique/identity}
   :artifact/case           {:db/index true}
   :artifact/sub-case       {:db/index true}
   :artifact/language       {}
   :artifact/qualified-name {:db/index true}
   :artifact/public         {}
   :artifact/source-file    {}
   :artifact/source-line    {}
   :artifact/fields         {:db/cardinality :db.cardinality/many}

   ;; ── Reified edges (Step B) ──────────────────────────────────────────
   ;; Metadata-bearing edges (projects today) as db entities — a plain ref
   ;; datom is a triple with nowhere to hang :projection-kind / :validity.
   ;; Plain kernel relations stay ref datoms; this shape is the additive
   ;; seam for observes/reads/writes (condition/scope) when forced.
   :edge/id                 {:db/unique :db.unique/identity}
   :edge/kind               {:db/index true}
   :edge/from               {:db/valueType :db.type/ref}
   :edge/to                 {:db/valueType :db.type/ref}
   :edge/projection-kind    {:db/index true}
   :edge/validity           {:db/index true}})

(defn create []
  (d/empty-db schema))

(defmulti ^:private ->datoms sub/primitive-kind)

(defmethod ->datoms :Module [m]
  [{:entity/id (sub/id-of m)
    :entity/type :Module
    :entity/name (sub/name-of m)
    :entity/tag (vec (sub/tags-of m))}])

(defmethod ->datoms :Affordance [a]
  (let [shape       (sub/shape-of a)
        inputs-set  (when (and shape (= :arrow (:kind shape)))
                      (shape/type-names (:inputs shape)))
        outputs-set (when (and shape (= :arrow (:kind shape)))
                      (shape/type-names (:outputs shape)))]
    [(cond-> {:entity/id (sub/id-of a)
              :entity/type :Affordance
              :entity/name (sub/name-of a)
              :entity/tag (vec (sub/tags-of a))}
       (sub/role-of a)              (assoc :affordance/role (sub/role-of a))
       shape                        (assoc :affordance/shape (pr-str shape))
       (sub/formal-expression-of a) (assoc :affordance/formal-expression (pr-str (sub/formal-expression-of a)))
       (sub/doc-of a)               (assoc :affordance/doc (sub/doc-of a))
       (sub/returns-label-of a)     (assoc :affordance/returns-label (sub/returns-label-of a))
       (seq inputs-set)             (assoc :affordance/input-types inputs-set)
       (seq outputs-set)            (assoc :affordance/output-types outputs-set))]))

(defmethod ->datoms :State [s]
  [(cond-> {:entity/id (sub/id-of s)
            :entity/type :State
            :entity/name (sub/name-of s)
            :entity/tag (vec (sub/tags-of s))}
     (sub/shape-of s)
     (assoc :state/shape (sub/shape-of s)))])

(defn- field-name->keyword
  "Normalize a record field-name to a keyword. Field-names arrive as strings
   (substrate-level usage) or symbols (via the `record` construction macro)."
  [n]
  (cond
    (keyword? n) n
    (symbol? n)  (keyword (name n))
    (string? n)  (keyword n)
    :else        (keyword (str n))))

(defn- field-tuples
  "For each [field-name parsed-shape] pair, emit one [field-name-kw type-name]
   tuple per distinct type-name appearing in the shape. Used to derive the
   :type/fields cardinality-many attribute for record-shaped Types."
  [fields]
  (into #{}
        (for [[fname fshape] fields
              tname (shape/type-names fshape)]
          [(field-name->keyword fname) tname])))

(defn- field-shape-tuples
  "For each [field-name parsed-shape] pair, emit one
   [field-name-kw shape-edn-pr-str] tuple. Used to derive the
   :type/field-shapes cardinality-many attribute for record-shaped Types,
   preserving the full parsed compound shape (which `:type/fields` flattens
   to leaf type-names). Read consumers (e.g. shape-drift comparator) parse
   the shape string back to edn via `clojure.edn/read-string`.

   Why pr-str rather than store the edn map directly? Cardinality-many
   datom values must be comparable scalars in Datascript; nested edn maps
   compare structurally but the stringified form gives stable identity and
   matches the precedent set by `:affordance/shape`."
  [fields]
  (into #{}
        (for [[fname fshape] fields]
          [(field-name->keyword fname) (pr-str fshape)])))

(defmethod ->datoms :Type [t]
  (let [record? (= :record (:kind t))
        field-types-set (when record?
                          (shape/type-names {:kind :record :fields (:fields t)}))
        field-tuples-set (when record?
                           (field-tuples (:fields t)))
        field-shapes-set (when record?
                           (field-shape-tuples (:fields t)))]
    [(cond-> {:entity/id (sub/id-of t)
              :entity/type :Type
              :entity/name (sub/name-of t)
              :entity/tag (vec (sub/tags-of t))}
       (sub/doc-of t)         (assoc :type/doc (sub/doc-of t))
       (seq field-types-set)  (assoc :type/field-types field-types-set)
       (seq field-tuples-set) (assoc :type/fields field-tuples-set)
       (seq field-shapes-set) (assoc :type/field-shapes field-shapes-set))]))

(defmethod ->datoms :Relation [r]
  (let [to-val (sub/to-of r)
        to-ref (if (keyword? to-val)
                 to-val
                 [:entity/id to-val])]
    [[:db/add [:entity/id (sub/from-of r)] (sub/kind-of r) to-ref]]))

(defn transact! [db entity]
  (d/db-with db (->datoms entity)))

;; ---------------------------------------------------------------------------
;; Phase-6 content as datoms (Step B): artifacts + reified projects edges.
;; These let the analyzer transact its output into the canvas db instead of
;; assoc-ing it onto the model map; the map then derives from the db.
;; ---------------------------------------------------------------------------

(defn artifact-id-str
  "Deterministic :artifact/id for a Code.* artifact map: the identity tuple
   (sub-case, language, qualified-name) joined with '|'. Stable across
   rebuilds, so re-transaction upserts rather than duplicating."
  [artifact]
  (str (get-in artifact [:sub :case]) "|"
       (:language artifact) "|"
       (get-in artifact [:sub :qualified-name])))

(defn artifact-id-of-tuple
  "Same :artifact/id encoding, from an artifact identity tuple
   [sub-case language qualified-name] (the shape an artifact endpoint carries)."
  [[sub-case language qualified-name]]
  (str sub-case "|" language "|" qualified-name))

(defn artifact->datoms
  "Datoms for a Code.* artifact map (per fukan.model.artifact). The
   data-structure :fields are pr-str'd per the :type/field-shapes precedent
   (cardinality-many values must be comparable scalars; field types may be
   compound Malli vectors)."
  [artifact]
  (let [sub    (:sub artifact)
        fields (:fields sub)
        sloc   (:source-location sub)]
    [(cond-> {:artifact/id             (artifact-id-str artifact)
              :artifact/case           (:case artifact)
              :artifact/sub-case       (:case sub)
              :artifact/language       (:language artifact)
              :artifact/qualified-name (:qualified-name sub)}
       (some? (:public? sub)) (assoc :artifact/public (:public? sub))
       (:file sloc)           (assoc :artifact/source-file (:file sloc))
       (:line sloc)           (assoc :artifact/source-line (:line sloc))
       (seq fields)           (assoc :artifact/fields (into #{} (map pr-str) fields)))]))

(defn edge->datoms
  "Datoms for a reified metadata-bearing edge (projects today). `from` is a
   primitive endpoint whose stable-id is resolved to a primitive eid via the
   `stable->uuid` map; `to` is an artifact endpoint (identity tuple →
   :artifact/id lookup-ref). Identity (:edge/id) is
   (kind, from-stable, artifact-id, projection-kind) so re-transaction
   upserts. Returns [] when the `from` endpoint does not resolve (the
   artifact target is assumed transacted first)."
  [edge stable->uuid]
  (let [from-id   (get-in edge [:from :id])
        from-uuid (get stable->uuid from-id)
        art-id    (artifact-id-of-tuple (get-in edge [:to :id]))
        pk        (:projection-kind edge)]
    (if (nil? from-uuid)
      []
      [(cond-> {:edge/id   (str (name (:kind edge)) "|" from-id "|" art-id "|" pk)
                :edge/kind (:kind edge)
                :edge/from [:entity/id from-uuid]
                :edge/to   [:artifact/id art-id]}
         pk               (assoc :edge/projection-kind pk)
         (:validity edge) (assoc :edge/validity (:validity edge)))])))

(defn all-modules [db]
  (->> (d/q '[:find ?n :where [?e :entity/type :Module] [?e :entity/name ?n]] db)
       (map (fn [[n]] {:name n}))))

(defn affordances-in [db module-id]
  (d/q '[:find ?n
         :in $ ?mid
         :where [?m :entity/id ?mid]
                [?m :module/child ?a]
                [?a :entity/type :Affordance]
                [?a :entity/name ?n]]
       db module-id))

(defn children-of-module
  "Return [type name] pairs for all entities directly owned by module."
  [db module-id]
  (d/q '[:find ?t ?n
         :in $ ?mid
         :where [?m :entity/id ?mid]
                [?m :module/child ?c]
                [?c :entity/type ?t]
                [?c :entity/name ?n]]
       db module-id))
