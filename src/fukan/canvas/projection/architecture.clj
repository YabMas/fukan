(ns fukan.canvas.projection.architecture
  "Project a navigable ARCHITECTURE OVERVIEW — fukan's CODE-SIDE shape and its system map: its
   the code Subsystems, the Modules each clusters, and the declared `:may-depend` DAG, derived live
   from the model. Pure projection: model db → string. Read this instead of `ls canvas/architecture/`."
  (:require [clojure.string :as str]
            [fukan.cozo.query :as cq]))

(defn- ord
  "Coerce a `:rel/order` cell to a long for sorting — `cq/q` returns leaf cells natively.
   (:child / :may-depend are ordered `:*` slots, so :rel/order is always present.)"
  [x] (long x))

(defn- subsystem-line [db sub-eid sub-name]
  (let [mods (->> (cq/q '[:find ?mod ?mn ?o :in $ ?sub
                          :where [?r :rel/from ?sub] [?r :rel/kind :child] [?r :rel/to ?mod] [?mod :entity/name ?mn]
                                 [?r :rel/order ?o]]
                        db sub-eid)
                  (sort-by #(ord (nth % 2)))
                  (map (fn [[_ mn _]] mn)))
        deps (->> (cq/q '[:find ?tn ?o :in $ ?sub
                          :where [?r :rel/from ?sub] [?r :rel/kind :may-depend] [?r :rel/to ?t] [?t :entity/name ?tn]
                                 [?r :rel/order ?o]]
                        db sub-eid)
                  (sort-by #(ord (second %)))
                  (map first))]
    (str (format "  ◆ %-14s " sub-name) (str/join ", " mods)
         (when (seq deps) (str "   ⟶ " (str/join ", " deps))))))

(defn architecture-overview
  "Render fukan's subsystems + modules + the :may-depend DAG (string)."
  [db]
  (let [subs  (->> (cq/q '[:find ?s ?sn :where [?s :structure/of :canvas.vocab.code.subsystem/Subsystem] [?s :entity/name ?sn]] db)
                   (sort-by second))
        nmod  (count (cq/q '[:find ?m :where [?m :structure/of :canvas.vocab.code.module/Module]] db))]
    (str/join
     "\n"
     (concat
      ["FUKAN — projected architecture overview (code-side subsystems)"
       (str "  " nmod " modules · " (count subs) " subsystems · derived live from the model")
       ""
       "━━ SUBSYSTEMS — capability clusters of Modules (⟶ = may-depend) ━━"
       ""]
      (map (fn [[seid sname]] (subsystem-line db seid sname)) subs)))))
