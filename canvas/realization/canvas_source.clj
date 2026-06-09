(ns canvas.realization.canvas-source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-source`).

   `build` discovers the canvas namespaces, requires them, and assembles their interned
   instance-vars into one db — references between instances are ordinary var-refs resolved by the
   assembler, so there is no merge/cross-ref pass. `union-dbs` folds an extractor's code db onto
   the assembled design db. The db it builds is the kernel's shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.realization.kernel :as kernel]))

(Module canvas-source
  "Discover canvas specs, require + assemble them into the model db; fold extracted code in."
  (Kind Str) (Kind File) (Kind NsSymbol) (Kind EntityMap) (Kind RefTx) (Kind Eid) (Kind Unit)

  ;; discovery — scan canvas/ for *.clj and derive namespace symbols (internal)
  (Operation ^:private file->ns-segment "A path segment → its namespace segment."
    (signature [:=> [:catn [:seg Str]] Str]))
  (Operation ^:private file->ns-symbol "A relative path → its namespace symbol."
    (signature [:=> [:catn [:rel-path Str]] NsSymbol]) (calls file->ns-segment))
  (Operation ^:private canvas-root-dirs "The canvas/ root dirs on the classpath + fs."
    (signature [:=> [:cat] [:vector File]]) (performs :io))
  (Operation ^:private discover-canvas-files-in "The *.clj files under a root."
    (signature [:=> [:catn [:root File]] [:vector [:map [:root File] [:rel-path Str]]]]) (performs :io))
  (Operation ^:private discover-canvas-namespaces "All canvas namespace symbols."
    (signature [:=> [:cat] [:vector NsSymbol]]) (performs :io :stderr)
    (calls canvas-root-dirs discover-canvas-files-in file->ns-symbol))
  (Operation ^:private require-canvas-namespace "Require a namespace (throws on load failure)."
    (signature [:=> [:catn [:ns-sym NsSymbol]] Unit]) (performs :require :throws))
  (Operation ^:private canvas-namespaces "The canvas namespaces (pure delegation)."
    (signature [:=> [:cat] [:vector NsSymbol]]) (calls discover-canvas-namespaces))
  (Operation ^:private db->entity-maps "A db → its entity-maps + ref-txs (for union)."
    (signature [:=> [:catn [:db kernel/StructureDb]] [:map [:entity-maps [:vector EntityMap]] [:ref-txs [:vector RefTx]]]]))

  ;; the public API
  (Operation union-dbs "Fold the extractor's code db onto the assembled design db."
    (signature [:=> [:catn [:dbs [:vector kernel/StructureDb]]] kernel/StructureDb]) (calls db->entity-maps))
  (Operation build "Discover + require + assemble the canvas specs → the model db."
    (signature [:=> [:cat] kernel/StructureDb]) (performs :io :stderr :require)
    (calls discover-canvas-namespaces require-canvas-namespace)))
