(ns canvas.source.value-model
  "canvas_source modelled VALUE / expression-style (canvas.pipeline.vocab Shapes):
   compound shapes (list, record) are expressed structurally, and recurring leaves
   are value-identified (shared). The contrast spec to canvas.source.entity-model —
   built to test whether expression-granularity modelling earns its keep on a
   subsystem with genuinely nested data (records, lists, a record-of-lists)."
  (:require [fukan.canvas.core.structure :as s]
            [fukan.canvas.structures :refer [Type]]
            ;; vocab required so :Shape registers; Shape is authored inline (data)
            [canvas.pipeline.vocab :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "source.value"
      (Type "File") (Type "Str") (Type "Db") (Type "EntityMap") (Type "RefTx")

      ;; discover-files : File -> (list {root: File, rel-path: Str})
      (Stage "discover-files"
        (in [root File])
        (out [{:root File :rel-path Str}]))

      ;; db->entity-maps : Db -> {entity-maps: (list EntityMap), ref-txs: (list RefTx)}
      (Stage "db->entity-maps"
        (in [db Db])
        (out {:entity-maps [EntityMap] :ref-txs [RefTx]}))

      ;; merge-dbs : (list Db) -> Db
      (Stage "merge-dbs"
        (in [dbs [Db]])
        (out Db))

      ;; build : -> Db ; calls the others
      (Stage "build"
        (out Db)
        (calls discover-files db->entity-maps merge-dbs)))))
