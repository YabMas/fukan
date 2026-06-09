(ns demos.self.model.fukan
  "fukan's own SUBJECT, authored in the candidate re-grammar (`demos.self.vocab.core`).

   Read top-to-bottom it IS the design story, in seven forms:

     the Model is a graph made of one Primitive (defstructure);
     two Sources converge on it — author (intent, design↓), extract (reality, code↑): two ORIGINS;
     two Acts read it through one Lens (focus) — probe (analyse→finding), project (synthesise→artifact);
     one Correspondence is the inverse pair: extract ⊣ project across the model↔code boundary.

   The in-side has no focus pivot — a focus is a read-side query (see the Lens docstring). Compare
   against `canvas/domain/faculties.clj`: there the same system is ten Faculty nodes and four
   generic edges, with #1/#3/#4 left for the reader to infer. Here they are named."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.self.vocab.core
             :refer [Primitive Model Lens Output Source Act Correspondence]]))

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
    (focus "which slice of the model to attend to — a query, so read-side only")))

;; what the acts yield
(def finding  (Output "finding"  (doc "a reading to reason with — a View, or a gating Signal")))
(def artifact (Output "artifact" (doc "a target form built from the model — Blueprint, Instruction, Docs")))

;; #1 two ORIGINS in tension — intent vs reality (no focus pivot on the write side)
(def author
  (Source "author"
    (doc "design authored in by a human/LLM on the canvas — top-down intent")
    (into model) (polarity "design-down")))
(def extract
  (Source "extract"
    (doc "code lifted in from the target by the extractor — bottom-up reality")
    (into model) (polarity "code-up")))

;; #3 the OUT-dual — two complementary acts, one focus
(def probe
  (Act "probe"
    (doc "read the model through a focus → a finding (analysis)")
    (reads model) (through focus) (mode "analyse") (yields finding)))
(def project
  (Act "project"
    (doc "re-present the model through a focus → an artifact (synthesis)")
    (reads model) (through focus) (mode "synthesise") (yields artifact)))

;; #4 emergent — the loop closes: extract (in) ⋈ project (out) over the one graph
(def correspondence
  (Correspondence "correspondence"
    (doc "extract (code-up source) and project (synthesise act) are inverse over the one Model, so their disagreement is checkable as drift")
    (lifts extract) (lowers project)))

(defn build [] (a/assemble ['demos.self.model.fukan]))
