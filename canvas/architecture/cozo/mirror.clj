(ns canvas.architecture.cozo.mirror
  "Self-spec: `fukan.cozo.mirror` — reflect a datascript db into a Cozo db as
   typed EAV relations, so laws and readers can be checked against the Cozo side
   (the `cozo == datascript` oracle). TRANSITIONAL: removed at cut-over (P5), when
   Cozo becomes the substrate of record rather than a mirror. Generic over any
   datascript db (it reads datoms, not fukan's StructureDb specifically)."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]))

(Module cozo-mirror
  "Reflect every datom of a datascript db into Cozo's typed EAV relations (t_int /
   t_str / t_bool), partitioned by value type — the migration's verification bridge."
  (Operation rows-by-bucket
    "The db's datoms grouped into {:int/:str/:bool #{[e a v]…}} — the expected mirror contents (the oracle's datascript-side reference)."
    {:signature [:=> [:catn [:ds-db :any]] :any]})
  (Operation mirror
    "Open a fresh Cozo db and load every datom of ds-db into the typed EAV relations; returns the open db."
    {:signature [:=> [:catn [:ds-db :any]] :any]
     :delegates [db/open db/q]}))
