(ns canvas.architecture.subsystems
  "fukan's code-side SUBSYSTEMS — the five capability clusters its Modules form, and the intended
   architecture DAG (`:may-depend`). The architecture-quality laws (in `fukan.common.vocab.code.subsystem`,
   auto-active with the vocab) enforce that fukan's actual `module-depends` graph conforms to this
   declared DAG and that the DAG stays acyclic. This is the modelled reorganization of the flat module
   list — the architecture's shape, stated and checked. (Filesystem layout still mirrors src/; a
   directory move can follow later. Capability ≠ faculty: orchestration realizes no subject faculty.)"
  (:require [fukan.common.vocab.code.subsystem :refer [Subsystem]]
            [canvas.architecture.kernel.substrate :refer [core-substrate]]
            [canvas.architecture.kernel.structure :refer [core-structure]]
            [canvas.architecture.kernel.rules :refer [core-rules]]
            [canvas.architecture.kernel.lens :refer [core-lens]]
            [canvas.architecture.kernel.assemble :refer [assemble-faculty]]
            [canvas.architecture.kernel.typing :refer [typing]]
            [canvas.architecture.kernel.reflect :refer [core-reflect]]
            [canvas.architecture.ingestion.source :refer [canvas-source]]
            [canvas.architecture.ingestion.extraction :refer [extraction]]
            [canvas.architecture.projection.instance :refer [projection-instance]]
            [canvas.architecture.projection.grammar :refer [projection-grammar]]
            [canvas.architecture.projection.architecture :refer [architecture]]
            [canvas.architecture.orchestration.pipeline :refer [model-pipeline]]
            [canvas.architecture.orchestration.infra :refer [infra-model]]
            [canvas.architecture.orchestration.core :refer [core]]
            [canvas.architecture.orchestration.check :refer [check]]
            [canvas.architecture.cozo.db :refer [cozo-db]]
            [canvas.architecture.cozo.mirror :refer [cozo-mirror]]
            [canvas.architecture.cozo.build :refer [cozo-build]]
            [canvas.architecture.cozo.query :refer [cozo-query]]
            [canvas.architecture.cozo.rules :refer [cozo-rules]]
            [canvas.architecture.cozo.law :refer [cozo-law]]))

(declare ingestion)

(Subsystem kernel
  "The node substrate + the defstructure grammar + its derived rules + lens engine + assembler + the
   type-dialect plug-point + the Cozo QUERY LAYER (the datalog→CozoScript compiler `cozo-query` and its
   engine seam `cozo-db`) — foundational; depends on nothing. The query layer sits here because the
   kernel itself queries (the lens engine, the law engine), so it is infrastructure, not a peripheral
   subsystem: the query primitive lives in the kernel here, while the wider `cozo` cluster above holds
   the model assembly (mirror + native build) and the registered check engine."
  {:child [core-substrate core-structure core-rules core-lens core-reflect assemble-faculty typing cozo-db cozo-query cozo-rules]
   :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source extraction] :may-depend []})

(Subsystem cozo
  "The Cozo cluster — the EAV mirror (`cozo-mirror`, the datom→stored-relation insert), the native build
   (`cozo-build`), and the registered check ENGINE (`cozo-law`, wired into `structure/check` via the kernel
   plug-point). The query PRIMITIVE itself (`cozo-query`/`cozo-db`) has folded UP into the kernel query
   layer; what remains here is the model assembly + the law engine. Depends on the kernel."
  {:child [cozo-mirror cozo-build cozo-law] :may-depend [kernel]})

(Subsystem projection
  "The outbound render surface — the inspection print-duals that re-present the model
   as authored forms (instance/grammar) plus the system map (architecture). The act
   grammar (Lens/Projection/Check) and its engine live in the kernel (core-lens)."
  {:child [projection-instance projection-grammar architecture] :may-depend [kernel]})

(Subsystem orchestration
  "Lifecycle + composition root + CLI entries — coordinates ingestion onto the model. Realizes no subject
   faculty. Depends on cozo during the cut-over: the lifecycle holds a Cozo mirror of the model, and
   on projection because an entry point RENDERS: `check --format text` reports through the same
   instance print-dual the REPL does, rather than growing a second way to quote an offender."
  {:child [model-pipeline infra-model core check] :may-depend [kernel ingestion cozo projection]})
