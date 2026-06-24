(ns canvas.architecture.cozo.db
  "Self-spec: the Cozo engine seam — `fukan.cozo.db`. Open an in-memory Cozo
   database, run a CozoScript script to its rows, close it. The boundary the
   datascript→Cozo migration ports laws and readers across; the only module that
   knows the external `cozo-clj` exists. The db handle is opaque (modelled `:any`)."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]))

(Module cozo-db
  "Open/query/close over cozo-clj — the engine seam everything else speaks
   CozoScript strings and row vectors to."
  (Operation open "Open a fresh in-memory Cozo database."
    {:signature [:=> [:cat] :any]})
  (Operation close "Close a Cozo database, releasing its native resources."
    {:signature [:=> [:catn [:db :any]] :any]})
  (Operation q "Run a CozoScript script (optionally with a $params map) and return its :rows."
    {:signature [:=> [:catn [:db :any] [:script :string]] [:vector :any]]})
  (Operation with-db "Open a db, call (f db), and close it even on throw; returns f's value."
    {:signature [:=> [:catn [:f :any]] :any]}))
