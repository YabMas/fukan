(ns canvas.architecture.cozo.mirror
  "Self-spec: `fukan.cozo.mirror` — the Cozo substrate WRITE layer: `[e a v]` triples → typed EAV
   stored relations (t_int / t_str / t_bool), partitioned by value type. (Named `mirror` from when it
   reflected a datascript db; now simply the substrate's only write path.)"
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]))

(Module cozo-mirror
  "Write `[e a v]` triples into Cozo's typed EAV relations (t_int / t_str / t_bool), partitioned by
   value type — the substrate the native build assembles into."
  (Operation load-datoms
    "Open a fresh Cozo db and load [e a v] triples into the typed EAV relations; returns the open db. The substrate write the native build assembles into."
    {:signature [:=> [:catn [:triples :any]] db/CozoDb]
     :delegates [db/open db/q]})
  (Operation insert-datoms
    "INSERT [e a v] triples into an already-open Cozo db (:put, not :create) — the additive analog of load-datoms, for grounding extra datoms (the native grammar reflection) onto an existing substrate."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:triples :any]] db/CozoDb]
     :delegates [db/q]}))
