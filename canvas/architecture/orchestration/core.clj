(ns canvas.architecture.orchestration.core
  "Self-spec: fukan's ENTRY POINT — a boundary sketch of `fukan.core`, the CLI composition entry.
   `-main` drives the model lifecycle: it delegates to the `infra-model` facade to build the held
   model from a source path. Realizes no subject faculty (orchestration). Lives in the
   `orchestration` subsystem with `infra-model`, so the `core → infra-model` coupling is
   intra-subsystem — the declared `:delegates` that closes that uncovered call."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.orchestration.infra :as infra]))

(Module core
  "The CLI entry point / composition root — drives the model lifecycle."
  (Operation -main "Entry point: build the held Model from a src path, then hand off."
    {:delegates [infra/load-model]}))
