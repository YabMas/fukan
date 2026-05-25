(ns fukan.canvas.library.closure
  "Module closure declarations. `exports` is a Clojure macro rather than a
   `defconstructor`-built lift because its body consists of bare positional
   names rather than form-grammar clauses. This is a deliberate special case;
   future canvas vocabulary may follow the same pattern when form-grammar
   doesn't fit the natural syntax."
  (:require [fukan.canvas.helpers :as h]
            [datascript.core :as d]))

(defn find-entity-by-name [db nm]
  (-> (d/q '[:find ?e
             :in $ ?n
             :where [?e :entity/name ?n]]
           db (str nm))
      ffirst))

(defmacro exports
  "Tag the listed declarations as :exported. Must appear inside `within-module`
   after the named declarations have been transacted.

   Names that don't match any declaration in the current canvas are silently
   ignored — exports is not a typecheck."
  [& names]
  `(let [db# @h/*store*]
     (doseq [n# '~names]
       (when-let [eid# (find-entity-by-name db# n#)]
         (swap! h/*store*
                #(d/db-with % [{:db/id eid# :entity/tag :exported}]))))))
