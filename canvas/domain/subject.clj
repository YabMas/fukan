(ns canvas.domain.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, authored in the subject grammar
   (`canvas.vocabulary.subject`). Read top-to-bottom it IS the design, in these forms:

     the Model is a graph made of one Primitive (defstructure);
     two Sources converge on it — author (intent, design↓), extract (reality, code↑);
     a Lens reads it (a focus → a sub-graph); a Projection synthesises from it (renders the
       model through a lens into an artifact) — the read and the synthesis are not twins;
     one Correspondence is the inverse pair: extract ⊣ project across the model↔code boundary.

   Which code Modules BUILD each of these is NOT stated here (the domain stays pure) — it is the
   `Realization` seam in `canvas.correspondence`, the verify-down link of the tower."
  (:require [canvas.vocabulary.subject :refer [Primitive Model Lens Source Projection Correspondence]]
            [lib.grouping :refer [Grouping]]))

;; #2 the floor — drill-down
(Primitive defstructure
  "a composition of slots + datalog laws — the single primitive the whole graph is made of")

;; the hub
(Model ^{:name "Model"} model
  "the unified structure graph — fukan's hub; everything orbits it"
  {:made-of defstructure})

;; #1 two ORIGINS in tension — intent vs reality
(Source author
  "design authored in by a human/LLM on the canvas — top-down intent"
  {:into     model
   :polarity "design-down"})

(Source extract
  "code lifted in from the target by the extractor — bottom-up reality"
  {:into     model
   :polarity "code-up"})

;; #3 the read act — a lens IS the reading (no separate probe wrapping it)
(Lens focus
  "read the model through a focus → a sub-graph to reason with (a View, or a gating Signal)"
  {:reads model
   :focus "the slice of the model the read attends to"})

;; the synthesis act — built on the lens, not a twin of it
(Projection project
  "re-present the model through a focus → a target artifact (Blueprint, Instruction, Docs) — i.e. materialization"
  {:through focus})

;; #4 emergent — the loop closes: extract (in) ⊣ project (out) over the one graph
(Correspondence correspondence
  "extract (code-up source) and project (the projection) are inverse over the one Model, so their disagreement is checkable as drift"
  {:lifts  extract
   :lowers project})

(Grouping subject
  {:child [defstructure model author extract focus project correspondence]})
