(ns canvas.architecture.ingestion.extraction
  "Self-spec: fukan's extraction PLUG-POINT (`fukan.model.extraction`) — the slot where a project
   registers its one custom code FACT extractor (a fn `Path → Facts`). `build-model` runs it via
   `extract-facts` WITHOUT naming it (keeps the pipeline generic); the composition root supplies it
   with `register-fact-extractor!`. Both operations mutate/read the registry slot (`:state`)."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.patterns.plug-point :refer [PlugPoint]]))

(Module extraction
  "The extraction plug-point — register and run the project's code FACT extractor."
  (Kind Path
    "A filesystem path to the source ROOT — the code root a project's extractor reads.
     The single source-root Kind: `build-model` and `load-model` adopt it (the same value
     flows in from the CLI → build-model → extract-facts)."
    :string)
  (Kind Unit)
  (Kind Facts
    "The extraction facts {:roots :ground} — the Module/Operation roots plus a post-build :ground
     closure ((db)→db) the extractor supplies to ground engine-specific derived edges (the :calls
     graph). What the native Cozo build consumes, produced by the registered fact extractor."
    :map)
  (Operation register-fact-extractor! "Register the project's FACT extractor (a fn Path → Facts)."
    {:signature [:=> [:catn [:f :any]] Unit]
     :performs  [:state]})
  (Operation extract-facts
    "Run the registered fact extractor over a code-root → its {:roots :ground} facts (or empty
     facts when none is registered). Routes to the registered project extractor, a `fukan.common.vocab`
     tool (the Clojure extractor) outside this built-system self-model — the dispatch seam points
     beyond what `architecture/` models."
    {:signature [:=> [:catn [:code-root Path]] Facts]
     :performs  [:state]}))

;; the extraction plug-point — a pattern-tier node drawn OVER the module (it names its :owner; the
;; module stays closed): a project's per-LANGUAGE code extractor plugs in (external-by-design;
;; fukan's is the Clojure extractor). The build consults the registry, naming no specific extractor.
(PlugPoint FactExtractor
  {:shape [:=> [:catn [:code-root Path]] Facts]
   :owner extraction})
