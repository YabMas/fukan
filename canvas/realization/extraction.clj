(ns canvas.realization.extraction
  "Self-spec: fukan's extraction PLUG-POINT (`fukan.model.extraction`) — the slot where a project
   registers its one custom code extractor (a fn `Path → StructureDb`). `build-model` runs it via
   `run-extractor` WITHOUT naming it (keeps the pipeline generic); the composition root supplies it
   with `register-extractor!`. Both operations mutate/read the registry slot (`:state`)."
  (:require [lib.code :refer [Kind Operation Subsystem]]
            [canvas.realization.kernel :as kernel]))

(Subsystem extraction
  "The extraction plug-point — register and run the project's code extractor."
  (Kind Extractor)
  (Kind Path "A filesystem path to the source ROOT — the code root a project's extractor reads.
              The single source-root Kind: `build-model` and `load-model` adopt it (the same value
              flows in from the CLI → build-model → run-extractor).")
  (Kind Unit)
  (Operation register-extractor! "Register the project's extractor (a fn Path → StructureDb)."
    (signature [:=> [:catn [:f Extractor]] Unit]) (performs :state))
  (Operation run-extractor "Run the registered extractor over a code-root → its structure db."
    (signature [:=> [:catn [:code-root Path]] kernel/StructureDb]) (performs :state)))
