(ns canvas.architecture.subsystems
  "fukan's code-side SUBSYSTEMS — the five capability clusters its Modules form, and the intended
   architecture DAG (`:may-depend`). The `lib.arch` quality laws (activated by
   `canvas.architecture.quality`) enforce that fukan's actual `module-depends` graph conforms to this
   declared DAG and that the DAG stays acyclic. This is the modelled reorganization of the flat module
   list — the architecture's shape, stated and checked. (Filesystem layout still mirrors src/; a
   directory move can follow later. Capability ≠ faculty: orchestration realizes no subject faculty.)"
  (:require [lib.code :refer [Subsystem]]
            [canvas.architecture.kernel.substrate :refer [core-substrate]]
            [canvas.architecture.kernel.structure :refer [core-structure]]
            [canvas.architecture.kernel.rules :refer [core-rules]]
            [canvas.architecture.kernel.lens :refer [core-lens]]
            [canvas.architecture.kernel.assemble :refer [assemble-faculty]]
            [canvas.architecture.kernel.typing :refer [typing]]
            [canvas.architecture.kernel.malli :refer [malli]]
            [canvas.architecture.ingestion.source :refer [canvas-source]]
            [canvas.architecture.ingestion.clojure :refer [target-clojure]]
            [canvas.architecture.reading.correspondence :refer [target-correspondence]]
            [canvas.architecture.ingestion.extraction :refer [extraction]]
            [canvas.architecture.reading.probes :refer [probes]]
            [canvas.architecture.reading.finding :refer [finding-faculty]]
            [canvas.architecture.projection.materialize :refer [materialize]]
            [canvas.architecture.projection.instance :refer [projection-instance]]
            [canvas.architecture.projection.grammar :refer [projection-grammar]]
            [canvas.architecture.projection.overview :refer [overview architecture]]
            [canvas.architecture.orchestration.pipeline :refer [model-pipeline]]
            [canvas.architecture.orchestration.infra :refer [infra-model]]
            [canvas.architecture.orchestration.core :refer [core]]))

(declare ingestion)

(Subsystem kernel
  "The node substrate + the defstructure grammar + its derived rules + lens engine + assembler + the
   type-dialect plug-point — foundational; depends on nothing."
  {:child [core-substrate core-structure core-rules core-lens assemble-faculty typing] :may-depend []})

(Subsystem dialect
  "A pluggable type dialect — a self-contained LEAF the kernel's typing plug-point dispatches to. It
   depends on nothing: the kernel reaches IN (dispatch), the dialect never reaches back into kernel
   internals. `:may-depend []` is the teeth — a dialect bridge that delegated to a kernel internal
   (the pre-SPI `reflect` reaching `value-literal->iv`) would violate conformance. Today: fukan's malli."
  {:child [malli] :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source target-clojure extraction] :may-depend [kernel]})

(Subsystem reading
  "Lenses over the graph: probe dispatch + the model↔code correspondence + the Finding output type."
  {:child [probes target-correspondence finding-faculty] :may-depend [kernel]})

(Subsystem projection
  "Graph → artifacts: materialization + the instance/grammar print-duals + the system-map overviews."
  {:child [materialize projection-instance projection-grammar overview architecture] :may-depend [kernel]})

(Subsystem orchestration
  "Lifecycle + composition root + CLI entry — coordinates ingestion onto the model. Realizes no subject
   faculty. Depends on `dialect` because the composition root (infra-model) wires the project dialect in."
  {:child [model-pipeline infra-model core] :may-depend [kernel ingestion dialect]})
