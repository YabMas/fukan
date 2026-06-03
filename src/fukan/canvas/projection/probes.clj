(ns fukan.canvas.projection.probes
  "Implemented probes — the LLM-authored leaves whose specs are projected from the model.
   probe-patterns implements the Instruction projected from the `patterns` probe."
  (:require [datascript.core :as d]))

(defn probe-patterns
  "Recurring structures across the model. Returns {:lens \"patterns\" :gating false
   :finding <a list of strings, one per recurring structure>}.

   A 'recurring structure' is a structural triplet (source-tag, relation-kind, target-tag)
   that appears in more than one distinct reified relation in the model. Each such triplet
   is a repeated architectural pattern — a structural signature of how concepts are
   connected throughout the design. Triplets appearing only once are unique connections,
   not patterns.

   Each finding string is formatted as: \"<count>× <from-tag> -[<rel-kind>]-> <to-tag>\",
   sorted descending by occurrence count."
  [target-db]
  {:lens    "patterns"
   :gating  false
   :finding (->> (d/q '[:find ?ft ?rk ?tt (count ?r)
                         :where [?r :rel/from ?f] [?r :rel/kind ?rk] [?r :rel/to ?t]
                                [?f :structure/of ?ft] [?t :structure/of ?tt]]
                       target-db)
                 (filter #(> (last %) 1))
                 (sort-by last >)
                 (mapv (fn [[ft rk tt cnt]]
                         (str cnt "× " ft " -[" rk "]-> " tt))))})
