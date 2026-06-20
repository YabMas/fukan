(ns canvas.architecture.kernel.assemble
  "Self-spec: the global ASSEMBLER (`fukan.canvas.core.assemble`) — a boundary sketch. Turns
   instance-bearing vars (or explicit `[id InstanceValue]` roots) into the kernel's `StructureDb`:
   stamp identity, walk inline values, transact nodes then rels so lookup-refs resolve. The single
   assembly path BOTH canvas ingestion and the code extractor build through; references between
   instances are ordinary var-refs resolved here, so there is no separate merge/cross-ref pass.
   Builds on the kernel's `create` constructor (`:delegates`); the discovery/identity internals are
   not sketched."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.substrate :as substrate]))

;; entity-name "assemble" (corresponds to the ns) on a distinct var, so the nested `assemble`
;; Operation can keep its own `assemble` var without colliding with the Module's.
(Module ^{:name "assemble"} assemble-faculty
  "Assemble instance-bearing vars / [id InstanceValue] roots into one StructureDb."
  (Operation assemble-vars
    "Build a StructureDb from an explicit collection of instance-bearing vars."
    {:signature [:=> [:catn [:vars [:vector :any]]] substrate/StructureDb]
     :delegates [substrate/create]})               ; builds on the kernel's StructureDb constructor
  (Operation assemble-instances
    "Build a StructureDb from explicit [id InstanceValue] roots — the extractor's path."
    {:signature [:=> [:catn [:id+ivs [:vector :any]]] substrate/StructureDb]
     :delegates [substrate/create]})
  (Operation emit-instances
    "Walk [id InstanceValue] roots into {:nodes :rels} maps WITHOUT transacting — for builders
     that merge into an existing db (the grammar reflector, the malli dialect)."
    {:signature [:=> [:catn [:id+ivs [:vector :any]]] :any]})
  (Operation assemble
    "Scan namespaces for instance-vars and build one StructureDb."
    {:signature [:=> [:catn [:ns-syms [:vector :symbol]]] substrate/StructureDb]}))
