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
            [canvas.architecture.projection.design :refer [projection-design]]
            [canvas.architecture.projection.prose :refer [projection-prose]]
            [canvas.architecture.projection.view :refer [projection-view]]
            [canvas.architecture.web.explorer :refer [explorer]]
            [canvas.architecture.orchestration.pipeline :refer [model-pipeline]]
            [canvas.architecture.orchestration.infra :refer [infra-model]]
            [canvas.architecture.orchestration.repl :refer [repl]]
            [canvas.architecture.orchestration.core :refer [core]]
            [canvas.architecture.orchestration.cli :refer [cli]]
            [canvas.architecture.cozo.db :refer [cozo-db]]
            [canvas.architecture.cozo.mirror :refer [cozo-mirror]]
            [canvas.architecture.cozo.build :refer [cozo-build]]
            [canvas.architecture.cozo.query :refer [cozo-query]]
            [canvas.architecture.cozo.rules :refer [cozo-rules]]
            [canvas.architecture.cozo.law :refer [cozo-law]]))

(declare ingestion)

(Subsystem kernel
  "The PRESENTATION and the model it is checked against: the node substrate, the defstructure grammar
   and the rules and laws it derives, the assembler, the type-dialect plug-point and grammar reflection.
   The kernel DEFINES — it states what a declaration lowers to and what a model is — and evaluates
   nothing: no module here queries, checks or knows which engine does. Depends on nothing, and that
   empty `:may-depend` is what keeps the engine out: a kernel module reaching for the query layer is a
   cross-subsystem dependency the conformance law reports."
  {:child [core-substrate core-structure core-rules core-reflect assemble-faculty typing]
   :may-depend []})

(Subsystem ingestion
  "The in-fold: discover/assemble design specs + extract code, folded onto the model."
  {:child [canvas-source extraction] :may-depend []})

(Subsystem cozo
  "The ENGINE — every module that knows the model lives in Cozo: the cozo-clj seam (`cozo-db`), the
   typed stored relations' write path (`cozo-mirror`) and decode (`cozo-rules`), the native build
   (`cozo-build`), the datalog→CozoScript compiler behind `q`/`entity` (`cozo-query`), and law
   evaluation (`cozo-law`, the home of `check`). It interprets the presentation the kernel defines and
   depends on the kernel one way. Everything above reads the model by handing `q` datalog as data, so
   which engine answers is decided here and nowhere else."
  {:child [cozo-db cozo-mirror cozo-rules cozo-build cozo-query cozo-law] :may-depend [kernel]})

(Subsystem projection
  "The outbound read surface — selections and renderings of a model: the act grammar
   (Lens/Projection/Check) and its selection engine (core-lens), the inspection print-duals that
   re-present the model as authored forms (instance/grammar), the system map (architecture), and the
   visible graph a view spec asks for (view). Reads the model through the engine's `q`."
  {:child [core-lens projection-instance projection-grammar projection-design projection-prose architecture projection-view] :may-depend [kernel cozo]})

(Subsystem web
  "The browser surface — serves readings of a model it is handed and holds none. Depends on
   projection for what it serves, on the kernel for the model's type, and on the engine for the
   reads it makes itself; it does not know a model is held, built or reloaded, which is why
   orchestration depends on it and not the reverse."
  {:child [explorer] :may-depend [kernel cozo projection]})

(Subsystem orchestration
  "Lifecycle + composition root + CLI entries — coordinates ingestion onto the model. Realizes no subject
   faculty. Depends on cozo during the cut-over: the lifecycle holds a Cozo mirror of the model, and
   on projection because an entry point RENDERS: `check --format text` reports through the same
   instance print-dual the REPL does, rather than growing a second way to quote an offender. The
   cockpit's read commands (`repl`) render the held model back out through those same print-duals
   (architecture/grammar/instance). On web because the cockpit is what starts the explorer and
   hands it the held model."
  {:child [model-pipeline infra-model repl core cli] :may-depend [kernel ingestion cozo projection web]})
