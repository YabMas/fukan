(ns canvas.architecture.cozo.db
  "Self-spec: the Cozo engine seam — `fukan.cozo.db`. Open an in-memory Cozo
   database, run a CozoScript script to its rows, close it. The only module that
   knows the external `cozo-clj` exists. The db handle is an opaque external
   (`Kind CozoDb`, bodyless — honestly shapeless)."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module cozo-db
  "Open/query/close over cozo-clj — the engine seam everything else speaks
   CozoScript strings and row vectors to."
  (Kind CozoDb)                          ; the cozo-clj db handle — an opaque external (no shape)
  (Operation open "Open a fresh in-memory Cozo database."
    {:signature [:=> [:cat] CozoDb]})
  (Operation close "Close a Cozo database, releasing its native resources."
    {:signature [:=> [:catn [:db CozoDb]] :any]})
  (Operation q "Run a CozoScript script (optionally with a $params map) and return its :rows."
    ;; `([db script] [db script params])` — a defaults chain, which is ONE operation with an
    ;; optional parameter rather than two, and `[:? …]` says exactly that: the regex matches 2 or
    ;; 3 arguments. Declaring only the shorter arity, as this did until 2026-08-29, hid one.
    {:signature [:=> [:catn [:db CozoDb] [:script :string] [:params [:? :map]]] [:vector :any]]})
  (Operation with-db "Open a db, call (f db), and close it even on throw; returns f's value."
    {:signature [:=> [:catn [:f [:=> [:catn [:db CozoDb]] :any]]] :any]}))
