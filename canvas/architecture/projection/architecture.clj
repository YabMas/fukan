(ns canvas.architecture.projection.architecture
  "Self-spec: the ARCHITECTURE OVERVIEW projection (`fukan.canvas.projection.architecture`) — fukan's
   system map: its `lib.code` Subsystems, the Modules each clusters, and the declared `:may-depend`
   DAG, derived live from the held model. A pure graph→text projection over the kernel's shared
   `StructureDb`; a leaf (no cross-module calls — it reads lib vocab + the graph directly)."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]))

(Module architecture
  "The code-side architecture overview — subsystems, their modules, and the :may-depend DAG."
  (Operation architecture-overview "Render the subsystem clustering + the :may-depend DAG from the held model."
    {:signature [:=> [:catn [:model substrate/StructureDb]] :string]}))
