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

   This is the layer that realizes the overview's `Target` faculty. Modelled
   faithfully like canvas-source: every fn is a Stage with its shaped I/O + calls.
   The two modules share the `StructureDb` Kind (one node, the unified graph both
   produce and read)."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.model.kernel :as kernel]))

(def Path        (Kind))
(def Analysis    (Kind))
(def StructureDb (Kind))

;; the Clojure extractor — source paths → code structures merged into a db
(def analyze (Stage (in [paths [Path]]) (out Analysis) (performs :io)))   ; clj-kondo run!
(def extract
  (Stage (in [paths [Path]]) (out StructureDb) (performs :io)             ; reads source (no eval)
    (calls analyze)))

(def target-clojure
  (Module "target.clojure" (child Path Analysis StructureDb analyze extract)))

;; the model↔code correspondence — drift as a query over the unified graph
(def StageName     (Kind))
(def OperationName (Kind))

(def unrealized-stages
  (Stage (in [db StructureDb]) (out [StageName])                ; spec→code gaps (via the law)
    (calls kernel/check)))
(def unrealized-operations
  (Stage (in [db StructureDb]) (out [OperationName])))      ; code→spec gaps (a query)

(def target-correspondence
  (Module "target.correspondence"
    (child StructureDb StageName OperationName unrealized-stages unrealized-operations)))
