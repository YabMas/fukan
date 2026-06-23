(ns canvas.architecture.ingestion.source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-source`) — a
   boundary sketch.

   `build` discovers the canvas namespaces, requires them, and assembles their interned
   instance-vars into one db — references between instances are ordinary var-refs resolved by the
   assembler, so there is no merge/cross-ref pass. `union-dbs` folds an extractor's code db onto
   the assembled design db. The db it builds is the kernel's shared `StructureDb`. `canvas-namespaces`
   is the discovery half exposed on its own because the build pipeline consumes it directly. (Require
   and the entity-map fold remain internals — not sketched.)"
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.assemble :as assemble]))

(Module canvas-source
  "Discover canvas specs, require + assemble them into the model db; fold extracted code in."
  (Operation union-dbs "Fold the extractor's code db onto the assembled design db."
    {:signature [:=> [:catn [:dbs [:vector substrate/StructureDb]]] substrate/StructureDb]
     :delegates [substrate/create]})               ; builds on the kernel's StructureDb constructor
  (Operation canvas-namespaces "Discover the canvas namespaces on the classpath (the build's input list)."
    {:signature [:=> [:cat] [:vector :any]]
     :performs  [:io]})
  (Operation build "Discover + require + assemble the canvas specs → the model db."
    {:signature [:=> [:cat] substrate/StructureDb]
     :performs  [:io :stderr :require :throws]
     :delegates [assemble/assemble]}))          ; assembles the discovered instance-vars
