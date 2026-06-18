(ns canvas.architecture.subsystems
  "fukan's code-side SUBSYSTEMS — the five capability clusters its Modules form, and the intended
   architecture DAG (`:may-depend`). The `lib.arch` quality laws (activated by
   `canvas.architecture.quality`) enforce that fukan's actual `module-depends` graph conforms to this
   declared DAG and that the DAG stays acyclic. This is the modelled reorganization of the flat module
   list — the architecture's shape, stated and checked. (Filesystem layout still mirrors src/; a
   directory move can follow later. Capability ≠ faculty: orchestration realizes no subject faculty.)"
  (:require [lib.code :refer [Subsystem]]
            [canvas.architecture.kernel.structure :refer [core-structure]]
            [canvas.architecture.kernel.rules :refer [core-rules]]
            [canvas.architecture.kernel.lens :refer [core-lens]]
            [canvas.architecture.ingestion.source :refer [canvas-source]]
            [canvas.architecture.ingestion.clojure :refer [target-clojure]]
            [canvas.architecture.reading.correspondence :refer [target-correspondence]]
            [canvas.architecture.ingestion.extraction :refer [extraction]]
            [canvas.architecture.reading.probes :refer [probes probe-code]]
            [canvas.architecture.projection.materialize :refer [materialize]]
            [canvas.architecture.projection.instance :refer [projection-instance]]
            [canvas.architecture.projection.grammar :refer [projection-grammar]]
            [canvas.architecture.orchestration.pipeline :refer [model-pipeline]]
            [canvas.architecture.orchestration.infra :refer [infra-model]]))

(declare ingestion)

(Subsystem kernel
  "The defstructure substrate + its derived rules + lens engine — foundational; depends on nothing."
  {:child [core-structure core-rules core-lens] :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source target-clojure extraction] :may-depend [kernel]})

(Subsystem reading
  "Lenses over the graph: probe dispatch + the model↔code correspondence."
  {:child [probes target-correspondence probe-code] :may-depend [kernel]})

(Subsystem projection
  "Graph → artifacts: materialization + the instance/grammar print-duals."
  {:child [materialize projection-instance projection-grammar] :may-depend [kernel]})

(Subsystem orchestration
  "Lifecycle + composition root — coordinates ingestion onto the model. Realizes no subject faculty."
  {:child [model-pipeline infra-model] :may-depend [kernel ingestion]})
