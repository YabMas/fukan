(ns canvas.architecture.ingestion.clojure
  "Self-spec: the CLOJURE EXTRACTOR — one registered extractor of the agnostic extraction
   plug-point (`canvas.architecture.ingestion.extraction`). Reads source via clj-kondo (no eval)
   and emits code structures into a db (`fukan.target.clojure`). The composition root registers it
   at the plug-point; `build-model` runs whatever is registered, naming no specific extractor."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.assemble :as assemble]))

(Module target-clojure
  "The Clojure extractor — reads source via clj-kondo (no eval) and emits code structures into a db."
  (Kind Path :string)
  (Operation extract "Extract code structures from source paths into the shared StructureDb."
    {:signature [:=> [:catn [:paths [:vector Path]]] substrate/StructureDb]
     :performs  [:io :throws]
     :delegates [assemble/assemble-instances]}))   ; emits the extracted instances into a db
