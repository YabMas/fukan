(ns fukan.canvas.projection.architecture
  "Project a navigable ARCHITECTURE OVERVIEW — fukan's CODE-SIDE shape: its `lib.code` Subsystems, the
   Modules each clusters (annotated with the subject faculty they realize, if any), and the declared
   `:may-depend` DAG. The dual of `overview/system-overview` (which renders the SUBJECT): this renders
   the realizing architecture. Pure projection: model db → string. Read this instead of `ls canvas/architecture/`."
  (:require [clojure.string :as str]
            [datascript.core :as d]))

(defn- module-role
  "The short faculty name a module realizes (e.g. \"Model\"), via its :realizes tag joined to the
   reflected concept node; nil if the module realizes no faculty."
  [db mod-eid]
  (ffirst (d/q '[:find ?cn :in $ ?m
                 :where [?m :val/realizes ?tag]
                        [?c :structure/of :lib.grammar/Structure] [?c :val/tag ?tag] [?c :entity/name ?cn]]
               db mod-eid)))

(defn- subsystem-line [db sub-eid sub-name]
  (let [mods (->> (d/q '[:find ?mod ?mn ?o :in $ ?sub
                         :where [?r :rel/from ?sub] [?r :rel/kind :child] [?r :rel/to ?mod] [?mod :entity/name ?mn]
                                [(get-else $ ?r :rel/order -1) ?o]]
                       db sub-eid)
                  (sort-by #(nth % 2))
                  (map (fn [[meid mn _]] (if-let [r (module-role db meid)] (str mn " [" r "]") mn))))
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
  (let [subs  (->> (d/q '[:find ?s ?sn :where [?s :structure/of :lib.code/Subsystem] [?s :entity/name ?sn]] db)
                   (sort-by second))
        nmod  (count (d/q '[:find ?m :where [?m :structure/of :lib.code/Module]] db))]
    (str/join
     "\n"
     (concat
      ["FUKAN — projected architecture overview (code-side subsystems)"
       (str "  " nmod " modules · " (count subs) " subsystems · derived live from the model")
       ""
       "━━ SUBSYSTEMS — capability clusters of Modules ([Faculty] = realized role; ⟶ = may-depend) ━━"
       ""]
      (map (fn [[seid sname]] (subsystem-line db seid sname)) subs)))))
