(ns fukan.canvas.core.substrate.store
  (:require [datascript.core :as d]
            [fukan.canvas.core.shape :as shape]
            [fukan.canvas.core.substrate :as sub]))

(def ^:private schema
  {:entity/id           {:db/unique :db.unique/identity}
   :entity/type         {:db/index true}
   :entity/name         {:db/index true}
   :module/child        {:db/cardinality :db.cardinality/many
                         :db/valueType :db.type/ref}
   :entity/tag          {:db/cardinality :db.cardinality/many}
   :entity/alias        {:db/cardinality :db.cardinality/many}
   :references          {:db/cardinality :db.cardinality/many}
   :affordance/doc          {:db/index true}
   :affordance/input-types  {:db/cardinality :db.cardinality/many}
   :affordance/output-types {:db/cardinality :db.cardinality/many}
   :type/doc                {:db/index true}
   :type/field-types        {:db/cardinality :db.cardinality/many}})

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
       (seq inputs-set)             (assoc :affordance/input-types inputs-set)
       (seq outputs-set)            (assoc :affordance/output-types outputs-set))]))

(defmethod ->datoms :State [s]
  [(cond-> {:entity/id (sub/id-of s)
            :entity/type :State
            :entity/name (sub/name-of s)
            :entity/tag (vec (sub/tags-of s))}
     (sub/shape-of s)
     (assoc :state/shape (sub/shape-of s)))])

(defmethod ->datoms :Type [t]
  (let [field-types-set (when (= :record (:kind t))
                          (shape/type-names {:kind :record :fields (:fields t)}))]
    [(cond-> {:entity/id (sub/id-of t)
              :entity/type :Type
              :entity/name (sub/name-of t)
              :entity/tag (vec (sub/tags-of t))}
       (sub/doc-of t)         (assoc :type/doc (sub/doc-of t))
       (seq field-types-set)  (assoc :type/field-types field-types-set))]))

(defmethod ->datoms :Relation [r]
  (let [to-val (sub/to-of r)
        to-ref (if (keyword? to-val)
                 to-val
                 [:entity/id to-val])]
    [[:db/add [:entity/id (sub/from-of r)] (sub/kind-of r) to-ref]]))

(defn transact! [db entity]
  (d/db-with db (->datoms entity)))

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
