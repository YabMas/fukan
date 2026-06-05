(ns canvas.model.extraction
  "Self-spec: fukan's extraction PLUG-POINT (`fukan.model.extraction`) — the slot
   where a project registers its one custom code extractor (a fn `Path → StructureDb`).
   `build-model` runs the registered extractor via `run-extractor` WITHOUT naming
   it, which keeps the pipeline generic; the project's composition root (infra.model)
   supplies the extractor with `register-extractor!`.

   Modelled faithfully like canvas-source — each fn a Stage with its shaped I/O.
   Both stages mutate/read the registry slot (`:state`)."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]))

(def Extractor   (Kind))
(def Path        (Kind))
(def StructureDb (Kind))
(def Unit        (Kind))

;; register the project's extractor (a fn Path → StructureDb) into the slot
(def register-extractor!
  (Stage (in [f Extractor]) (out Unit) (performs :state)))
;; run the registered extractor over a code-root → its structure db
(def run-extractor
  (Stage (in [code-root Path]) (out StructureDb) (performs :state)))

(def extraction
  (Module (child Extractor Path StructureDb Unit
                              register-extractor! run-extractor)))
