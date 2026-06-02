(ns canvas.model.canvas-source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-
   source`), modelled at expression-granularity with `canvas.pipeline.vocab`
   (Stage + value-identified Shape). Faithful to the source: every fn is a Stage
   with its shaped input/output and call edges.

   This is where value-identity pays off in a real fukan subsystem: the same leaf
   shapes recur across the discovery/merge pipeline and collapse to one node each —
   `Db` (db->entity-maps, merge-dbs ×2, build), `NsSymbol` (file->ns-symbol,
   discover-canvas-namespaces, load+resolve, canvas-namespaces), `File`, `Str`. The
   discovery entry `{root: File, rel-path: Str}` and the extraction
   `{entity-maps: (list EntityMap), ref-txs: (list RefTx)}` are record shapes."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "canvas-source"
      (Kind "Str") (Kind "File") (Kind "NsSymbol") (Kind "Db")
      (Kind "EntityMap") (Kind "RefTx") (Kind "BuildCanvasFn")

      ;; discovery — scan canvas/ for *.clj and derive namespace symbols
      (Stage "file->ns-segment"  (in [seg Str])      (out Str))               ; pure
      (Stage "file->ns-symbol"   (in [rel-path Str]) (out NsSymbol)           ; pure
        (calls file->ns-segment))
      (Stage "canvas-root-dirs"  (out [File]) (performs :io))                 ; classpath + fs
      (Stage "discover-canvas-files-in" (in [root File])
        (out [{:root File :rel-path Str}]) (performs :io))                    ; file-seq
      (Stage "discover-canvas-namespaces" (out [NsSymbol]) (performs :io :stderr)
        (calls canvas-root-dirs discover-canvas-files-in file->ns-symbol))
      (Stage "load-and-resolve-build-canvas" (in [ns-sym NsSymbol]) (out BuildCanvasFn)
        (performs :require :throws))                                          ; require + throw on load failure
      (Stage "canvas-namespaces" (out [NsSymbol])
        (calls discover-canvas-namespaces))                                   ; pure delegation

      ;; merge — combine per-spec structure dbs into one
      (Stage "db->entity-maps" (in [db Db])
        (out {:entity-maps [EntityMap] :ref-txs [RefTx]}))                    ; pure (datascript)
      (Stage "merge-dbs" (in [dbs [Db]]) (out Db)                             ; pure
        (calls db->entity-maps))

      ;; build — the model
      (Stage "build" (out Db) (performs :io :stderr :require)
        (calls discover-canvas-namespaces load-and-resolve-build-canvas merge-dbs)))))
