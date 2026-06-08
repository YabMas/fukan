(ns fukan.dialect.malli
  "The malli render bridge — a Schema subgraph → a malli data-form (code).
   The materialize/LOWER direction of the schema dialect. A pure library
   exposing `render`; wired to the schema-dialect plug-point at the composition
   root (`fukan.infra.model`). Pure data: no malli library needed."
  (:require [datascript.core :as d]))

(defn- children
  "Target eids of `from`'s reified `rel` relations, in :rel/order order
   (relations with no :rel/order sort as 0 — harmless for unordered slots)."
  [db from rel]
  (->> (d/q '[:find ?to ?ord :in $ ?from ?k :where
              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/to ?to]
              [(get-else $ ?r :rel/order 0) ?ord]]
            db from rel)
       (sort-by second)
       (mapv first)))

(defn render
  "Render the Schema at `eid` in `db` back to a malli data-form."
  [db eid]
  (let [ent   (d/entity db eid)
        kind  (:val/kind ent)
        props (cond-> {}
                (:val/min ent)   (assoc :min (:val/min ent))
                (:val/max ent)   (assoc :max (:val/max ent))
                (:val/regex ent) (assoc :re  (:val/regex ent)))]
    (case kind
      ("int" "string" "boolean" "keyword" "double")
      (if (seq props) [(keyword kind) props] (keyword kind))
      ("vector" "set" "sequential")
      [(keyword kind) (render db (first (children db eid :of)))]
      ("tuple" "or" "and")
      (into [(keyword kind)] (map #(render db %) (children db eid :of)))
      "map"
      (into [:map]
            (map (fn [feid]
                   (let [f   (d/entity db feid)
                         sk  (first (children db feid :schema))
                         kw  (keyword (:val/key f))
                         sub (render db sk)]
                     (if (:val/optional f) [kw {:optional true} sub] [kw sub])))
                 (children db eid :field)))
      "enum"
      (into [:enum]
            (map (fn [ceid] (keyword (:val/value (d/entity db ceid))))
                 (children db eid :choice)))
      "ref"
      (symbol (:entity/name (d/entity db (first (children db eid :names)))))
      (throw (ex-info (str "cannot render schema kind: " kind) {:eid eid :kind kind})))))
