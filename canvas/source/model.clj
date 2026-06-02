(ns canvas.source.model
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
            [fukan.canvas.structures :refer [Type]]
            [canvas.pipeline.vocab :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "canvas-source"
      (Type "Str") (Type "File") (Type "NsSymbol") (Type "Db")
      (Type "EntityMap") (Type "RefTx") (Type "BuildCanvasFn")

      ;; discovery — scan canvas/ for *.clj and derive namespace symbols
      (Stage "file->ns-segment"  (in [seg Str])      (out Str))
      (Stage "file->ns-symbol"   (in [rel-path Str]) (out NsSymbol)
        (calls file->ns-segment))
      (Stage "canvas-root-dirs"  (out [File]))
      (Stage "discover-canvas-files-in" (in [root File])
        (out [{:root File :rel-path Str}]))
      (Stage "discover-canvas-namespaces" (out [NsSymbol])
        (calls canvas-root-dirs discover-canvas-files-in file->ns-symbol))
      (Stage "load-and-resolve-build-canvas" (in [ns-sym NsSymbol]) (out BuildCanvasFn))
      (Stage "canvas-namespaces" (out [NsSymbol])
        (calls discover-canvas-namespaces))

      ;; merge — combine per-spec structure dbs into one
      (Stage "db->entity-maps" (in [db Db])
        (out {:entity-maps [EntityMap] :ref-txs [RefTx]}))
      (Stage "merge-dbs" (in [dbs [Db]]) (out Db)
        (calls db->entity-maps))

      ;; build — the model
      (Stage "build" (out Db)
        (calls discover-canvas-namespaces load-and-resolve-build-canvas merge-dbs)))))
