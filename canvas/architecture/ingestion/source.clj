(ns canvas.architecture.ingestion.source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-source`) — a
   boundary sketch.

   `build` discovers the canvas namespaces, requires them, and assembles their interned
   instance-vars into one db — references between instances are ordinary var-refs resolved by the
   assembler, so there is no merge/cross-ref pass. `union-dbs` folds an extractor's code db onto
   the assembled design db. The db it builds is the kernel's shared `StructureDb`. (Discovery,
   namespace derivation and the entity-map fold are internals — extraction's job, not sketched.)"
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.subject :as subj]))

(Module canvas-source
  "Discover canvas specs, require + assemble them into the model db; fold extracted code in."
  {:realizes subj/Source}                        ; faculty role: the design-down half of the Source in-fold
  (Operation union-dbs "Fold the extractor's code db onto the assembled design db."
    {:signature [:=> [:catn [:dbs [:vector kernel/StructureDb]]] kernel/StructureDb]})
  (Operation build "Discover + require + assemble the canvas specs → the model db."
    {:signature [:=> [:cat] kernel/StructureDb]
     :performs  [:io :stderr :require]}))
