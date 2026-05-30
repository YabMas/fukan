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
   ;; ── Tag-applications (canonical classification spine) ───────────────
   ;; A Node's kind/role becomes a reified tag-application: the canonical
   ;; truth that the tag-agnostic core knows, and that vocabularies plug
   ;; into. :entity/type and :affordance/role above are retained as a
   ;; derived index over these while consumers migrate onto them.
   :tagapp/id           {:db/unique :db.unique/identity}
   :tagapp/node         {:db/valueType :db.type/ref}
   :tagapp/tag          {:db/index true}
   ;; ── Tag-definitions (the vocabulary as declared data) ───────────────
   ;; Projected from fukan.canvas.vocab.registry in enrich-substrate so the
   ;; vocabulary is queryable on the db surface alongside the model it
   ;; classifies — and is the seam a generic declare-node consumes.
   :tagdef/tag          {:db/unique :db.unique/identity}
   :tagdef/family       {:db/index true}
   ;; The refinement-lattice parent tag: this tag refines (is-a) its parent.
   ;; Explicit on the tag-definition, or derived from :family (the super-tag).
   ;; The classification stratum's refines*/kind-of/family-of close over these.
   :tagdef/refines      {:db/index true}
   :tagdef/payload      {:db/index true}
   :tagdef/doc          {}
   ;; ── Reified shapes (payload de-blob, arc-D) ─────────────────────────
   ;; A node's typed payload (affordance arrow, record-type fields) as a
   ;; walkable :shape/* entity tree instead of a pr-str blob — the canonical,
   ;; queryable form. :node/shape links a node to its payload root. Ordered
   ;; children (:shape/items for sum variants and tuple elems, :shape/fields
   ;; for record fields) carry :shape/index; record fields also carry
   ;; :shape/field-name (verbatim).
   :node/shape          {:db/valueType :db.type/ref}
   :shape/id            {:db/unique :db.unique/identity}
   :shape/kind          {:db/index true}
   :shape/name          {}
   :shape/target        {}
   :shape/index         {}
   :shape/field-name    {}
   :shape/inner         {:db/valueType :db.type/ref}
   :shape/elem          {:db/valueType :db.type/ref}
   :shape/key           {:db/valueType :db.type/ref}
   :shape/val           {:db/valueType :db.type/ref}
   :shape/inputs        {:db/valueType :db.type/ref}
   :shape/outputs       {:db/valueType :db.type/ref}
   :shape/items         {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :shape/fields        {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
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

;; ── Shape reification (payload de-blob) ──────────────────────────────────

(defn- amend-root
  "Set extra attrs on the root entity (the one whose :shape/id = root-id)
   within a shape's datom list. Used to stamp :shape/index / :shape/field-name
   onto an ordered child's root without descending into its subtree."
  [datoms root-id extra]
  (mapv #(if (= (:shape/id %) root-id) (merge % extra) %) datoms))

(defn shape->datoms
  "Reify a parsed shape map (per fukan.canvas.core.shape/parse) into :shape/*
   entity maps. Returns [root-id datoms] where root-id is the :shape/id of the
   tree root. Field-names are preserved verbatim."
  [shape]
  (let [;; Normalise raw keyword leaves (:String, :model/Model) that the
        ;; low-level arrow/record-of helpers leave unparsed; the construction
        ;; lifts already parse, so this is a no-op for them.
        shape (if (keyword? shape) (shape/parse shape) shape)
        id    (str (random-uuid))
        base  {:shape/id id :shape/kind (:kind shape)}]
    (case (:kind shape)
      :atomic [id [(assoc base :shape/name (:name shape))]]
      :ref    [id [(assoc base :shape/target (:target shape))]]
      :optional (let [[c cds] (shape->datoms (:inner shape))]
                  [id (conj cds (assoc base :shape/inner [:shape/id c]))])
      :list (let [[c cds] (shape->datoms (:elem shape))]
              [id (conj cds (assoc base :shape/elem [:shape/id c]))])
      :set  (let [[c cds] (shape->datoms (:elem shape))]
              [id (conj cds (assoc base :shape/elem [:shape/id c]))])
      :map  (let [[kc kds] (shape->datoms (:key shape))
                  [vc vds] (shape->datoms (:val shape))]
              [id (-> (vec kds) (into vds)
                      (conj (assoc base :shape/key [:shape/id kc] :shape/val [:shape/id vc])))])
      :arrow (let [[ic ids] (shape->datoms (:inputs shape))
                   [oc ods] (shape->datoms (:outputs shape))]
               [id (-> (vec ids) (into ods)
                       (conj (assoc base :shape/inputs [:shape/id ic] :shape/outputs [:shape/id oc])))])
      :sum (let [parts (mapv shape->datoms (:variants shape))
                 child (vec (mapcat (fn [i [c cds]] (amend-root cds c {:shape/index i}))
                                    (range) parts))
                 refs  (mapv (fn [[c _]] [:shape/id c]) parts)]
             [id (conj child (assoc base :shape/items refs))])
      :tuple (let [parts (mapv shape->datoms (:elems shape))
                   child (vec (mapcat (fn [i [c cds]] (amend-root cds c {:shape/index i}))
                                      (range) parts))
                   refs  (mapv (fn [[c _]] [:shape/id c]) parts)]
               [id (conj child (assoc base :shape/items refs))])
      :record (let [parts (mapv (fn [[fname fshape]] (conj (shape->datoms fshape) fname))
                                (:fields shape))
                    child (vec (mapcat (fn [i [c cds fname]]
                                         (amend-root cds c {:shape/index i :shape/field-name fname}))
                                       (range) parts))
                    refs  (mapv (fn [[c _ _]] [:shape/id c]) parts)]
                [id (conj child (assoc base :shape/fields refs))]))))

(defn read-reified-shape
  "Reconstruct a parsed-shape map from a reified :shape/* entity (eid or
   lookup-ref). Inverse of shape->datoms. Field-names returned verbatim."
  [db eid]
  (let [e (d/entity db eid)
        rec #(read-reified-shape db (:db/id %))
        ordered (fn [coll] (->> coll (sort-by :shape/index) vec))]
    (case (:shape/kind e)
      :atomic {:kind :atomic :name (:shape/name e)}
      :ref    {:kind :ref :target (:shape/target e)}
      :optional {:kind :optional :inner (rec (:shape/inner e))}
      :list {:kind :list :elem (rec (:shape/elem e))}
      :set  {:kind :set :elem (rec (:shape/elem e))}
      :map  {:kind :map :key (rec (:shape/key e)) :val (rec (:shape/val e))}
      :arrow {:kind :arrow :inputs (rec (:shape/inputs e)) :outputs (rec (:shape/outputs e))}
      :sum   {:kind :sum :variants (mapv rec (ordered (:shape/items e)))}
      :tuple {:kind :tuple :elems (mapv rec (ordered (:shape/items e)))}
      :record {:kind :record
               :fields (mapv (fn [c] [(:shape/field-name c) (rec c)])
                             (ordered (:shape/fields e)))})))

(defn- tagapp-maps
  "Reified tag-application entities for a node — the canonical classification
   truth. One per primary kind/role tag (nil tags are skipped). The legacy
   :entity/type / :affordance/role datoms are retained as a derived index over
   these while consumers migrate onto them."
  [node-uuid primary-tag]
  (when primary-tag
    [{:tagapp/id   (str node-uuid "|" primary-tag)
      :tagapp/node [:entity/id node-uuid]
      :tagapp/tag  primary-tag}]))

(defmulti ^:private ->datoms sub/primitive-kind)

(defmethod ->datoms :Module [m]
  (into [{:entity/id (sub/id-of m)
          :entity/type :Module
          :entity/name (sub/name-of m)
          :entity/tag (vec (sub/tags-of m))}]
        (tagapp-maps (sub/id-of m) (sub/tag-of m))))

(defmethod ->datoms :Affordance [a]
  (let [shape       (sub/shape-of a)
        inputs-set  (when (and shape (= :arrow (:kind shape)))
                      (shape/type-names (:inputs shape)))
        outputs-set (when (and shape (= :arrow (:kind shape)))
                      (shape/type-names (:outputs shape)))
        [shape-root shape-datoms] (if shape (shape->datoms shape) [nil nil])]
    (-> (vec (or shape-datoms []))
        (conj (cond-> {:entity/id (sub/id-of a)
                       :entity/type :Affordance
                       :entity/name (sub/name-of a)
                       :entity/tag (vec (sub/tags-of a))}
                (sub/role-of a)              (assoc :affordance/role (sub/role-of a))
                (sub/formal-expression-of a) (assoc :affordance/formal-expression (sub/formal-expression-of a))
                (sub/doc-of a)               (assoc :affordance/doc (sub/doc-of a))
                (sub/returns-label-of a)     (assoc :affordance/returns-label (sub/returns-label-of a))
                (seq inputs-set)             (assoc :affordance/input-types inputs-set)
                (seq outputs-set)            (assoc :affordance/output-types outputs-set)
                shape-root                   (assoc :node/shape [:shape/id shape-root])))
        (into (tagapp-maps (sub/id-of a) (sub/tag-of a))))))

(defmethod ->datoms :State [s]
  (into
   [(cond-> {:entity/id (sub/id-of s)
             :entity/type :State
             :entity/name (sub/name-of s)
             :entity/tag (vec (sub/tags-of s))}
      (sub/shape-of s)
      (assoc :state/shape (sub/shape-of s)))]
   (tagapp-maps (sub/id-of s) (sub/tag-of s))))

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

(defmethod ->datoms :Type [t]
  (let [record? (= :record (:type-kind t))
        field-types-set (when record?
                          (shape/type-names {:kind :record :fields (:fields t)}))
        field-tuples-set (when record?
                           (field-tuples (:fields t)))
        [shape-root shape-datoms] (if record?
                                    (shape->datoms {:kind :record :fields (:fields t)})
                                    [nil nil])]
    (-> (vec (or shape-datoms []))
        (conj (cond-> {:entity/id (sub/id-of t)
                       :entity/type :Type
                       :entity/name (sub/name-of t)
                       :entity/tag (vec (sub/tags-of t))}
                (sub/doc-of t)         (assoc :type/doc (sub/doc-of t))
                (seq field-types-set)  (assoc :type/field-types field-types-set)
                (seq field-tuples-set) (assoc :type/fields field-tuples-set)
                shape-root             (assoc :node/shape [:shape/id shape-root])))
        (into (tagapp-maps (sub/id-of t) (sub/tag-of t))))))

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
