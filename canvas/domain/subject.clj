(ns canvas.domain.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, authored in the subject grammar
   (`canvas.vocabulary.subject`). Read top-to-bottom it IS the design, in these forms:

     the Model is a graph made of one Primitive (defstructure);
     two Sources converge on it — author (intent, design↓), extract (reality, code↑);
     two Acts read it — probe (analyse → finding), project (synthesise → artifact);
     one Correspondence is the inverse pair: extract ⊣ project across the model↔code boundary.

   Which code Modules BUILD each of these is NOT stated here (the domain stays pure) — it is the
   `Realization` seam in `canvas.correspondence`, the verify-down link of the tower. Coexists with
   the `Faculty` self-model (`canvas.domain.faculties`) during the migration."
  (:require [canvas.vocabulary.subject :refer [Primitive Model Output Lens Source Act Correspondence]]
            [lib.grouping :refer [Grouping]]))

;; #2 the floor — drill-down
(def defstructure
  (Primitive "defstructure"
    (doc "a composition of slots + datalog laws — the single primitive the whole graph is made of")))

;; the hub
(def model
  (Model "Model"
    (doc "the unified structure graph — fukan's hub; everything orbits it")
    (made-of defstructure)))

;; the read-side focus (no write-side mirror)
(def focus
  (Lens "focus"
    (doc "which slice of the model to attend to — a query, so read-side only")
    (focus "the slice of the model an act attends to")))

;; what the acts yield
(def finding  (Output "finding"  (doc "a reading to reason with — a View, or a gating Signal")))
(def artifact (Output "artifact" (doc "a target form built from the model — Blueprint, Instruction, Docs")))

;; #1 two ORIGINS in tension — intent vs reality
(def author
  (Source "author"
    (doc "design authored in by a human/LLM on the canvas — top-down intent")
    (into model) (polarity "design-down")))
(def extract
  (Source "extract"
    (doc "code lifted in from the target by the extractor — bottom-up reality")
    (into model) (polarity "code-up")))

;; #3 two MODES of use
(def probe
  (Act "probe"
    (doc "read the model through a focus → a finding (analysis)")
    (reads model) (through focus) (mode "analyse") (yields finding)))
(def project
  (Act "project"
    (doc "re-present the model through a focus → an artifact (synthesis) — i.e. materialization")
    (reads model) (through focus) (mode "synthesise") (yields artifact)))

;; #4 emergent — the loop closes: extract (in) ⊣ project (out) over the one graph
(def correspondence
  (Correspondence "correspondence"
    (doc "extract (code-up source) and project (synthesise act) are inverse over the one Model, so their disagreement is checkable as drift")
    (lifts extract) (lowers project)))

(def subject
  (Grouping (child defstructure model focus finding artifact author extract probe project correspondence)))
