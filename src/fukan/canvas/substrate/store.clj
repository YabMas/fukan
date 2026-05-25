(ns fukan.canvas.substrate.store
  (:require [datascript.core :as d]
            [fukan.canvas.substrate :as sub]))

(def ^:private schema
  {:entity/id           {:db/unique :db.unique/identity}
   :entity/type         {:db/index true}
   :entity/name         {:db/index true}
   :affordance/module   {:db/valueType :db.type/ref}
   :state/module        {:db/valueType :db.type/ref}
   :module/child        {:db/cardinality :db.cardinality/many
                         :db/valueType :db.type/ref}
   :entity/tag          {:db/cardinality :db.cardinality/many}
   :references          {:db/cardinality :db.cardinality/many}})

(defn create []
  (d/empty-db schema))

(defmulti ^:private ->datoms sub/primitive-kind)

(defmethod ->datoms :Module [m]
  [{:entity/id (sub/id-of m)
    :entity/type :Module
    :entity/name (sub/name-of m)
    :entity/tag (vec (sub/tags-of m))}])

(defmethod ->datoms :Affordance [a]
  [(cond-> {:entity/id (sub/id-of a)
            :entity/type :Affordance
            :entity/name (sub/name-of a)
            :entity/tag (vec (sub/tags-of a))}
     (sub/module-of a)
     (assoc :affordance/module [:entity/id (sub/module-of a)])
     (sub/role-of a)
     (assoc :affordance/role (sub/role-of a))
     (sub/shape-of a)
     (assoc :affordance/shape (pr-str (sub/shape-of a))))])

(defmethod ->datoms :State [s]
  [{:entity/id (sub/id-of s)
    :entity/type :State
    :entity/name (sub/name-of s)
    :state/module [:entity/id (sub/module-of s)]
    :state/shape (sub/shape-of s)
    :entity/tag (vec (sub/tags-of s))}])

(defmethod ->datoms :Type [t]
  [{:entity/id (sub/id-of t)
    :entity/type :Type
    :entity/name (sub/name-of t)
    :entity/tag (vec (sub/tags-of t))}])

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
                [?a :affordance/module ?m]
                [?a :entity/name ?n]]
       db module-id))
