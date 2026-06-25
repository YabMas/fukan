(ns canvas.architecture.kernel.assemble
  "Self-spec: the global ASSEMBLER (`fukan.canvas.core.assemble`) — a boundary sketch. Walks
   authored InstanceValues (explicit `[id InstanceValue]` roots) into plain node/rel datom-MAPS:
   stamp identity, walk inline values, resolve refs. The maps are engine-neutral; the native Cozo
   build writes them to the substrate. References between instances are ordinary var-refs resolved
   here, so there is no merge/cross-ref pass; the identity internals are not sketched."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]))

;; entity-name "assemble" (corresponds to the ns) on a distinct var, so the nested Operation can
;; keep its own var without colliding with the Module's.
(Module ^{:name "assemble"} assemble-faculty
  "Walk instance-bearing roots into the engine-neutral {:nodes :rels} datom-maps the build writes."
  (Operation emit-instances
    "Walk [id InstanceValue] roots into {:nodes :rels} maps — what the native Cozo build (and the
     grammar reflector / malli dialect) assemble from."
    {:signature [:=> [:catn [:id+ivs [:vector :any]]] :any]
     :performs  [:throws]
     :delegates [substrate/value-content-key substrate/var-id]}))   ; stamps value / var identity on each node
