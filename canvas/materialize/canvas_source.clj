(ns canvas.materialize.canvas-source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-
   source`), modelled at expression-granularity with the fukan-on-fukan grammar
   (`canvas.materialize.vocab`: Operation + value-identified Shape). Faithful to the source:
   every fn is an Operation with its shaped input/output and call edges.

   `build` now discovers the canvas namespaces, requires them, and assembles their
   interned instance-vars into one db — references between instances are ordinary var
   refs resolved by the assembler, so there is no merge/cross-ref pass. `union-dbs`
   remains only to fold an extractor's code db onto the assembled design db.

   This is where value-identity pays off in a real fukan subsystem: the same leaf
   shapes recur across the discovery/union pipeline and collapse to one node each —
   `NsSymbol`, `File`, `Str` (the structure db it builds is the kernel's `StructureDb`)."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]))

(def Str       (Kind))
(def File      (Kind))
(def NsSymbol  (Kind))
(def EntityMap (Kind))
(def RefTx     (Kind))
(def Eid       (Kind))
(def Unit      (Kind))

;; discovery — scan canvas/ for *.clj and derive namespace symbols
(def file->ns-segment (Operation [seg Str] -> Str))       ; pure
(def file->ns-symbol
  (Operation [rel-path Str] -> NsSymbol                     ; pure
    (calls file->ns-segment)))
(def canvas-root-dirs (Operation [] -> [File] (performs :io)))     ; classpath + fs
(def discover-canvas-files-in
  (Operation [root File]
    -> [{:root File :rel-path Str}] (performs :io)))                           ; file-seq
(def discover-canvas-namespaces
  (Operation [] -> [NsSymbol] (performs :io :stderr)
    (calls canvas-root-dirs discover-canvas-files-in file->ns-symbol)))
(def require-canvas-namespace
  (Operation [ns-sym NsSymbol] -> Unit
    (performs :require :throws)))                                                 ; require + throw on load failure
(def canvas-namespaces
  (Operation [] -> [NsSymbol]
    (calls discover-canvas-namespaces)))                                          ; pure delegation

;; union — fold the extractor's code db onto the assembled design db
(def db->entity-maps
  (Operation [db kernel/StructureDb]
    -> {:entity-maps [EntityMap] :ref-txs [RefTx]}))                           ; pure (datascript)
(def union-dbs
  (Operation [dbs [kernel/StructureDb]] -> kernel/StructureDb                                     ; pure
    (calls db->entity-maps)))

;; build — discover + require + assemble → the model
(def build
  (Operation [] -> kernel/StructureDb (performs :io :stderr :require)
    (calls discover-canvas-namespaces require-canvas-namespace)))

(def canvas-source
  (Subsystem
    (exposes build union-dbs)                      ; the canvas-source API (pipeline calls these)
    (owns Str File NsSymbol EntityMap RefTx Eid Unit)
    (child file->ns-segment file->ns-symbol canvas-root-dirs discover-canvas-files-in
           discover-canvas-namespaces require-canvas-namespace canvas-namespaces
           db->entity-maps)))
