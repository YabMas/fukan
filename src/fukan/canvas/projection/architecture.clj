(ns fukan.canvas.projection.architecture
  "Project a navigable ARCHITECTURE OVERVIEW — fukan's CODE-SIDE shape and its system map: its
   `lib.code` Subsystems, the Modules each clusters, and the declared `:may-depend` DAG, derived live
   from the model. Pure projection: model db → string. Read this instead of `ls canvas/architecture/`."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

(defn- subsystem-line [db sub-eid sub-name]
  (let [mods (->> (d/q '[:find ?mod ?mn ?o :in $ ?sub
                         :where [?r :rel/from ?sub] [?r :rel/kind :child] [?r :rel/to ?mod] [?mod :entity/name ?mn]
                                [(get-else $ ?r :rel/order -1) ?o]]
                       db sub-eid)
                  (sort-by #(nth % 2))
                  (map (fn [[_ mn _]] mn)))
        deps (->> (d/q '[:find ?tn ?o :in $ ?sub
                         :where [?r :rel/from ?sub] [?r :rel/kind :may-depend] [?r :rel/to ?t] [?t :entity/name ?tn]
                                [(get-else $ ?r :rel/order -1) ?o]]
                       db sub-eid)
                  (sort-by second)
                  (map first))]
    (str (format "  ◆ %-14s " sub-name) (str/join ", " mods)
         (when (seq deps) (str "   ⟶ " (str/join ", " deps))))))

(defn architecture-overview
  "Render fukan's subsystems + modules + the :may-depend DAG (string)."
  [db]
  (let [subs  (->> (d/q '[:find ?s ?sn :where [?s :structure/of :canvas.vocab.code.subsystem/Subsystem] [?s :entity/name ?sn]] db)
                   (sort-by second))
        nmod  (count (d/q '[:find ?m :where [?m :structure/of :canvas.vocab.code.module/Module]] db))]
    (str/join
     "\n"
     (concat
      ["FUKAN — projected architecture overview (code-side subsystems)"
       (str "  " nmod " modules · " (count subs) " subsystems · derived live from the model")
       ""
       "━━ SUBSYSTEMS — capability clusters of Modules (⟶ = may-depend) ━━"
       ""]
      (map (fn [[seid sname]] (subsystem-line db seid sname)) subs)))))
