(ns canvas.architecture.subsystems
  "fukan's code-side SUBSYSTEMS — the five capability clusters its Modules form, and the intended
   architecture DAG (`:may-depend`). The architecture-quality laws (in `canvas.vocab.code.subsystem`,
   auto-active with the vocab) enforce that fukan's actual `module-depends` graph conforms to this
   declared DAG and that the DAG stays acyclic. This is the modelled reorganization of the flat module
   list — the architecture's shape, stated and checked. (Filesystem layout still mirrors src/; a
   directory move can follow later. Capability ≠ faculty: orchestration realizes no subject faculty.)"
  (:require [canvas.vocab.code.subsystem :refer [Subsystem]]
            [canvas.architecture.kernel.substrate :refer [core-substrate]]
            [canvas.architecture.kernel.structure :refer [core-structure]]
            [canvas.architecture.kernel.rules :refer [core-rules]]
            [canvas.architecture.kernel.lens :refer [core-lens]]
            [canvas.architecture.kernel.assemble :refer [assemble-faculty]]
            [canvas.architecture.kernel.typing :refer [typing]]
            [canvas.architecture.ingestion.source :refer [canvas-source]]
            [canvas.architecture.ingestion.extraction :refer [extraction]]
            [canvas.architecture.reading.probes :refer [probes]]
            [canvas.architecture.reading.finding :refer [finding-faculty]]
            [canvas.architecture.projection.materialize :refer [materialize]]
            [canvas.architecture.projection.instance :refer [projection-instance]]
            [canvas.architecture.projection.grammar :refer [projection-grammar]]
            [canvas.architecture.projection.architecture :refer [architecture]]
            [canvas.architecture.orchestration.pipeline :refer [model-pipeline]]
            [canvas.architecture.orchestration.infra :refer [infra-model]]
            [canvas.architecture.orchestration.core :refer [core]]
            [canvas.architecture.cozo.db :refer [cozo-db]]
            [canvas.architecture.cozo.mirror :refer [cozo-mirror]]
            [canvas.architecture.cozo.reading :refer [cozo-reading]]))

(declare ingestion)

(Subsystem kernel
  "The node substrate + the defstructure grammar + its derived rules + lens engine + assembler + the
   type-dialect plug-point — foundational; depends on nothing."
  {:child [core-substrate core-structure core-rules core-lens assemble-faculty typing] :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source extraction] :may-depend [kernel]})

(Subsystem cozo
  "The Cozo query engine (datascript→Cozo migration) — the engine seam + the EAV
   mirror that reflects the datascript substrate so laws/readers can be checked
   against Cozo (the oracle). A leaf during the migration: nothing in production
   depends on it yet, and it depends on no other faculty. TRANSITIONAL shape —
   folds into the kernel query layer at cut-over (P5)."
  {:child [cozo-db cozo-mirror cozo-reading] :may-depend []})

(Subsystem reading
  "Lenses over the graph: probe dispatch + the Finding output type. (The model↔code correspondence
   it composes now lives with the code vocab in `canvas.vocab.code.*` — vocab, not a self-modelled faculty.)"
  {:child [probes finding-faculty] :may-depend [kernel]})

(Subsystem projection
  "Graph → artifacts: materialization + the instance/grammar print-duals + the system-map overview."
  {:child [materialize projection-instance projection-grammar architecture] :may-depend [kernel]})

(Subsystem orchestration
  "Lifecycle + composition root + CLI entry — coordinates ingestion onto the model. Realizes no subject
   faculty."
  {:child [model-pipeline infra-model core] :may-depend [kernel ingestion]})
