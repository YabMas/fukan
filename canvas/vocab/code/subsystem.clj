(ns canvas.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module.
   (The `lib.arch` clean-architecture quality laws over Subsystems — conformance / acyclicity /
   membership — fold in here with the architecture-quality layer.)"
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocab.code.module :refer [Module]]))

(defstructure Subsystem
  "A cluster of Modules realizing a capability — the rung above Module in the grouping ladder
   (Grouping ⊂ Module ⊂ Subsystem). Owns its Modules (`:child`, ownership-on-owner) and DECLARES the
   subsystems it is allowed to depend on (`:may-depend` — the intended architecture DAG, as declared
   intent). `:may-depend` is a self-reference, exactly like `Operation :delegates` — the assembler
   resolves the var-refs."
  {:child      [:* Module]        ; the Modules this subsystem clusters
   :may-depend [:* Subsystem]})   ; the subsystems it is allowed to depend on (declared intent)
