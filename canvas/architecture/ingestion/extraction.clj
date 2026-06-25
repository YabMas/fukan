(ns canvas.architecture.ingestion.extraction
  "Self-spec: fukan's extraction PLUG-POINT (`fukan.model.extraction`) — the slot where a project
   registers its one custom code extractor (a fn `Path → StructureDb`). `build-model` runs it via
   `run-extractor` WITHOUT naming it (keeps the pipeline generic); the composition root supplies it
   with `register-extractor!`. Both operations mutate/read the registry slot (`:state`)."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]))

(Module extraction
  "The extraction plug-point — register and run the project's code extractor."
  (Kind Extractor [:=> [:catn [:code-root Path]] substrate/StructureDb])
  (Kind Path
    "A filesystem path to the source ROOT — the code root a project's extractor reads.
     The single source-root Kind: `build-model` and `load-model` adopt it (the same value
     flows in from the CLI → build-model → run-extractor)."
    :string)
  (Kind Unit)
  (Kind FactExtractor [:=> [:catn [:code-root Path]] Facts])
  (Kind Facts
    "The engine-agnostic extraction facts {:roots :var-usages} — the Module/Operation roots plus
     the var-usages used to ground the :calls graph. What the native Cozo build consumes (no
     datascript), produced by the registered fact extractor."
    :map)
  (Operation register-extractor! "Register the project's extractor (a fn Path → StructureDb)."
    {:signature [:=> [:catn [:f Extractor]] Unit]
     :performs  [:state]})
  (Operation run-extractor
    "Run the registered extractor over a code-root → its structure db. Routes to the registered
     project extractor, which is now a `canvas/vocab` tool (the Clojure extractor) outside this
     built-system self-model — so the dispatch seam points beyond what `architecture/` models."
    {:signature [:=> [:catn [:code-root Path]] substrate/StructureDb]
     :performs  [:state]})
  (Operation register-fact-extractor! "Register the project's FACT extractor (a fn Path → Facts)."
    {:signature [:=> [:catn [:f FactExtractor]] Unit]
     :performs  [:state]})
  (Operation extract-facts
    "Run the registered fact extractor over a code-root → its {:roots :var-usages} facts (or empty
     facts when none is registered). The engine-agnostic twin of run-extractor that the native
     Cozo build reads."
    {:signature [:=> [:catn [:code-root Path]] Facts]
     :performs  [:state]}))
