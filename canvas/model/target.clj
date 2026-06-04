(ns canvas.model.target
  "Self-spec: fukan's TARGET layer — how an analyzed codebase's structure is
   extracted INTO the Model, and how the Model's realization in that code is
   verified. Two namespaces, two modules:

     target.clojure        — the Clojure extractor (fukan.target.clojure): reads
                             source via clj-kondo (no eval) and emits code
                             structures (Operations owned by Modules) into a db.
     target.correspondence — the model↔code correspondence (fukan.target.correspondence):
                             `unrealized-stages` queries the unified graph for
                             modelled Stages with no realizing Operation (drift),
                             calling the kernel's `check`.

   This is the layer that realizes the overview's `Target` faculty — the mechanism
   by which a Target codebase feeds, and is reconciled with, the Model. Modelled
   faithfully like canvas-source: every fn is a Stage with its shaped I/O + calls."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    ;; the Clojure extractor — source paths → code structures merged into a db
    (s/within-module "target.clojure"
      (Kind "Path") (Kind "Analysis") (Kind "StructureDb")
      (Stage "analyze" (in [paths [Path]]) (out Analysis) (performs :io))         ; clj-kondo run!
      (Stage "extract" (in [paths [Path]]) (out StructureDb) (performs :io)       ; reads source (no eval)
        (calls analyze)))

    ;; the model↔code correspondence — drift as a query over the unified graph
    (s/within-module "target.correspondence"
      (Kind "StructureDb") (Kind "StageName")
      (Stage "unrealized-stages" (in [db StructureDb]) (out [StageName])          ; pure (datascript)
        (calls (across "core.structure" "check"))))))
