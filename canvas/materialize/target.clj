(ns canvas.materialize.target
  "Self-spec: fukan's TARGET layer — how an analyzed codebase's structure is
   extracted INTO the Model, and how the Model's realization in that code is
   verified. Two namespaces, two modules:

     target.clojure        — the Clojure extractor (fukan.target.clojure): reads
                             source via clj-kondo (no eval) and emits code
                             structures (Operations owned by Modules) into a db.
     target.correspondence — the model↔code correspondence (fukan.target.correspondence):
                             `drifted-operations` queries the unified graph for
                             modelled Operations with no realizing Operation (drift),
                             calling the kernel's `check`.

   This is the layer that realizes the overview's `Target` faculty. Modelled
   faithfully like canvas-source: every fn is an Operation with its shaped I/O + calls.
   The two modules share the `StructureDb` Kind (one node, the unified graph both
   produce and read)."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]))

(def Path        (Kind))
(def Analysis    (Kind))

;; the Clojure extractor — source paths → code structures merged into the shared
;; StructureDb (owned by core.structure — referenced, not redeclared)
(def analyze (Operation (in [paths [Path]]) (out Analysis) (performs :io)))   ; clj-kondo run!
(def extract
  (Operation (in [paths [Path]]) (out kernel/StructureDb) (performs :io)      ; reads source (no eval)
    (calls analyze)))

(def target-clojure
  (Subsystem "target.clojure"
     (exposes extract)                              ; the extractor entry point
     (child Path Analysis analyze)))

;; the model↔code correspondence — drift as a query over the unified graph
(def OperationName (Kind))

(def drifted-operations
  (Operation (in [db kernel/StructureDb]) (out [OperationName])      ; spec→code gaps (via the law)
    (calls kernel/check)))
(def uncovered-operations
  (Operation (in [db kernel/StructureDb]) (out [OperationName])))    ; code→spec gaps (a query)

(def target-correspondence
  (Subsystem "target.correspondence"
    (exposes drifted-operations uncovered-operations)   ; the drift / coverage queries
    (child OperationName)))
