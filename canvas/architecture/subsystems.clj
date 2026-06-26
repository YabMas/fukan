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
            [canvas.architecture.kernel.coverage :refer [core-coverage]]
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
            [canvas.architecture.cozo.build :refer [cozo-build]]
            [canvas.architecture.cozo.query :refer [cozo-query]]
            [canvas.architecture.cozo.law :refer [cozo-law]]))

(declare ingestion)

(Subsystem kernel
  "The node substrate + the defstructure grammar + its derived rules + lens engine + assembler + the
   type-dialect plug-point + the Cozo QUERY LAYER (the datalog→CozoScript compiler `cozo-query` and its
   engine seam `cozo-db`) — foundational; depends on nothing. The query layer sits here because the
   kernel itself queries (the lens engine, the law engine), so it is infrastructure, not a peripheral
   subsystem: the query primitive lives in the kernel here, while the wider `cozo` cluster above holds
   the model assembly (mirror + native build) and the registered check engine."
  {:child [core-substrate core-structure core-rules core-lens core-coverage assemble-faculty typing cozo-db cozo-query]
   :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source extraction] :may-depend [kernel]})

(Subsystem cozo
  "The Cozo cluster — the EAV mirror (`cozo-mirror`, the datom→stored-relation insert), the native build
   (`cozo-build`), and the registered check ENGINE (`cozo-law`, wired into `structure/check` via the kernel
   plug-point). The query PRIMITIVE itself (`cozo-query`/`cozo-db`) has folded UP into the kernel query
   layer; what remains here is the model assembly + the law engine. Depends on the kernel."
  {:child [cozo-mirror cozo-build cozo-law] :may-depend [kernel]})

(Subsystem reading
  "Lenses over the graph: probe dispatch + the Finding output type. (The model↔code correspondence
   it composes now lives with the code vocab in `canvas.vocab.code.*` — vocab, not a self-modelled faculty.)"
  {:child [probes finding-faculty] :may-depend [kernel]})

(Subsystem projection
  "Graph → artifacts: materialization + the instance/grammar print-duals + the system-map overview."
  {:child [materialize projection-instance projection-grammar architecture] :may-depend [kernel]})

(Subsystem orchestration
  "Lifecycle + composition root + CLI entry — coordinates ingestion onto the model. Realizes no subject
   faculty. Depends on cozo during the cut-over: the lifecycle holds a Cozo mirror of the model."
  {:child [model-pipeline infra-model core] :may-depend [kernel ingestion cozo]})
