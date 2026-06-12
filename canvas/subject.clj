(ns canvas.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, as ONE stratum.

   PROTOTYPE: this folds the former `canvas/vocabulary/subject.clj` (grammar) + `canvas/domain/
   subject.clj` (instances) into a single artifact. Rationale: the vocabulary/domain split earns its
   keep only when a grammar is REUSED (it can then drift independently of any one consumer); a
   grammar bespoke to a single model is locked to it — non-driftable — so the split is pure overhead
   and the two halves rhyme 1:1. The subject is exactly that bespoke case, so it lives as one
   stratum: each concept's structure followed by its (few) instances. Reusable grammars (`lib.*`)
   keep the split; the realization seam stays separate (`canvas.correspondence`) — that one crosses
   to code and genuinely IS driftable.

   Read top-to-bottom it IS the design:
     Nodes wired by Relations ARE the graph; the Form (defstructure) builds a Vocabulary over that
       substrate — the language a model is authored in (fukan ships none; bottom-up language building);
     one hub MODEL — that graph, authored in a Vocabulary;
     two SOURCES converge IN — author (intent, design↓), extract (reality, code↑);
     a LENS reads it; a PROJECTION synthesises from it (built on the lens, not a twin);
     one CORRESPONDENCE: extract ⊣ project, the inverse pair over the one Model."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Grouping]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

;; ── the floor (GRAMMAR ONLY) ──────────────────────────────────────────────────────────────────
;; Nodes + Relations are the substrate; the Form builds a Vocabulary over them. These are
;; TYPE-LEVEL facts — true of the Model KIND, not of one instance — so they live purely as grammar
;; (the slots below, reflected into the graph by lib.grammar). No singleton instances: a `(Node
;; node)` would witness no law, anchor no realization, and carry no data — a pure shadow of its type.

(defstructure Node
  "A node — the atom of the graph (by name+uuid, or by content when it is a value).")

(defstructure Relation
  "A reified, kinded edge wiring one Node to another. Nodes + Relations ARE the graph; everything
   else is built over them."
  {:wires Node})

(defstructure Form
  "`defstructure` — the one grammar-building form (fukan ships exactly this; everything else is grown
   with it — bottom-up language building). Working OVER the substrate — it TYPES a Node and COMPOSES
   its Relations (the slots) — it defines a named Structure; a Vocabulary is the set you build."
  {:types Node :composes Relation})

(defstructure Vocabulary
  "A grammar built with the Form — a set of Structures (each = slots + laws). The language a Model is
   AUTHORED IN: what a model can say is whatever its vocabularies define."
  {:built-with Form})

;; ── the hub: the one graph (its foundation — made-of/wired-by/authored-in — is the grammar above) ──

(defstructure Model
  "The hub: one unified GRAPH — many Nodes wired by many Relations — AUTHORED IN one or more
   Vocabularies. Spec and implementation live on this one graph. Its one-ness is an apparatus
   guarantee (one substrate), not a design choice — so #1's headline is the two ORIGINS, not the one
   graph."
  {:made-of     [:* Node]
   :wired-by    [:* Relation]
   :authored-in [:* Vocabulary]})
(Model ^{:name "Model"} model)

;; ── #1 two ORIGINS converge IN — intent vs reality ──

(defstructure Source
  "A way IN — content converging on the Model. The in-fold is two ORIGINS, one of each `:polarity` —
   design authored DOWN (intent), code extracted UP (reality). A Source has no focus: focus is a
   read-side query, and the write side has nothing to apply it to."
  {:into Model :polarity [:enum "design-down" "code-up"]}
  (law "the loop closes — every code-up source is matched by a correspondence"
    (matched-by :lifts :from Correspondence :when {:polarity "code-up"})))
(Source author  {:into model :polarity "design-down"})
(Source extract {:into model :polarity "code-up"})

;; ── #3 the Model is USED two ways (not twins) ──

(defstructure Lens
  "The READ act — a focus over the Model that resolves to a sub-graph. A lens is a QUERY, so it is
   intrinsically read-side; evaluating it IS the reading (there is no separate `Probe`). The lens
   catalog + the gating/contract that make a reading a Signal vs a View live lower, in
   `canvas.domain.lens` / `canvas.vocabulary.act`."
  {:reads Model :focus :String})
(Lens focus {:reads model :focus "the slice of the model the read attends to"})

(defstructure Projection
  "The SYNTHESIS act — re-presenting the Model in a target form (materialization), THROUGH a Lens. NOT
   a twin of the read: built ON the lens, doing work it does not (mapping kinds, contextualization).
   The full grammar lives lower in `canvas.vocabulary.act/Projection` (same concept, two altitudes —
   ns-precise scoping keeps the lower `has-any` law off this abstract faculty)."
  {:through Lens})
(Projection project {:through focus})

;; ── #4 emergent: extract (in) ⊣ project (out) over the one graph ──

(defstructure Correspondence
  "#4, EMERGENT — one Source and one Projection inverse over the one Model. `:lifts` the code-up
   Source (extract: code → model) and `:lowers` the Projection (project: model → code/artifact); the
   two compose to the identity on the shared graph, so their disagreement is checkable as drift. The
   within-SUBJECT relation; the subject→code seam is `Realization` in `canvas.correspondence`."
  {:lifts Source :lowers Projection}
  (law "a correspondence lifts a code-up source"
    (target :lifts {:polarity "code-up"})))
(Correspondence correspondence {:lifts extract :lowers project})

(Grouping subject
  {:child [model author extract focus project correspondence]})
