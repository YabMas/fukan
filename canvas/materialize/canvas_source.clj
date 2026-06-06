(ns canvas.materialize.canvas-source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-
   source`), modelled at expression-granularity with the fukan-on-fukan grammar
   (`canvas.language.{shape,op}`: Stage + value-identified Shape). Faithful to the source:
   every fn is a Stage with its shaped input/output and call edges.

   `build` now discovers the canvas namespaces, requires them, and assembles their
   interned instance-vars into one db — references between instances are ordinary var
   refs resolved by the assembler, so there is no merge/cross-ref pass. `union-dbs`
   remains only to fold an extractor's code db onto the assembled design db.

   This is where value-identity pays off in a real fukan subsystem: the same leaf
   shapes recur across the discovery/union pipeline and collapse to one node each —
   `Db`, `NsSymbol`, `File`, `Str`."
  (:require [canvas.language.shape :refer [Kind]]
            [canvas.language.op :refer [Stage]]
            [canvas.language.grouping :refer [Module]]))

(def Str       (Kind))
(def File      (Kind))
(def NsSymbol  (Kind))
(def Db        (Kind))
(def EntityMap (Kind))
(def RefTx     (Kind))
(def Eid       (Kind))
(def Unit      (Kind))

;; discovery — scan canvas/ for *.clj and derive namespace symbols
(def file->ns-segment (Stage (in [seg Str]) (out Str)))       ; pure
(def file->ns-symbol
  (Stage (in [rel-path Str]) (out NsSymbol)                     ; pure
    (calls file->ns-segment)))
(def canvas-root-dirs (Stage (out [File]) (performs :io)))     ; classpath + fs
(def discover-canvas-files-in
  (Stage (in [root File])
    (out [{:root File :rel-path Str}]) (performs :io)))                           ; file-seq
(def discover-canvas-namespaces
  (Stage (out [NsSymbol]) (performs :io :stderr)
    (calls canvas-root-dirs discover-canvas-files-in file->ns-symbol)))
(def require-canvas-namespace
  (Stage (in [ns-sym NsSymbol]) (out Unit)
    (performs :require :throws)))                                                 ; require + throw on load failure
(def canvas-namespaces
  (Stage (out [NsSymbol])
    (calls discover-canvas-namespaces)))                                          ; pure delegation

;; union — fold the extractor's code db onto the assembled design db
(def db->entity-maps
  (Stage (in [db Db])
    (out {:entity-maps [EntityMap] :ref-txs [RefTx]})))                           ; pure (datascript)
(def union-dbs
  (Stage (in [dbs [Db]]) (out Db)                                     ; pure
    (calls db->entity-maps)))

;; build — discover + require + assemble → the model
(def build
  (Stage (out Db) (performs :io :stderr :require)
    (calls discover-canvas-namespaces require-canvas-namespace)))

(def canvas-source
  (Module
    (child Str File NsSymbol Db EntityMap RefTx Eid Unit
           file->ns-segment file->ns-symbol canvas-root-dirs discover-canvas-files-in
           discover-canvas-namespaces require-canvas-namespace canvas-namespaces
           db->entity-maps union-dbs build)))
