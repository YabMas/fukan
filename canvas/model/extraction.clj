(ns canvas.model.extraction
  "Self-spec: fukan's extraction PLUG-POINT (`fukan.model.extraction`) — the slot
   where a project registers its one custom code extractor (a fn `Path → StructureDb`).
   `build-model` runs the registered extractor via `run-extractor` WITHOUT naming
   it, which keeps the pipeline generic; the project's composition root (infra.model)
   supplies the extractor with `register-extractor!`.

   Modelled faithfully like canvas-source — each fn a Stage with its shaped I/O.
   Both stages mutate/read the registry slot (`:state`)."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "extraction"
      (Kind "Extractor") (Kind "Path") (Kind "StructureDb") (Kind "Unit")
      ;; register the project's extractor (a fn Path → StructureDb) into the slot
      (Stage "register-extractor!" (in [f Extractor]) (out Unit) (performs :state))
      ;; run the registered extractor over a code-root → its structure db
      (Stage "run-extractor" (in [code-root Path]) (out StructureDb) (performs :state)))))
