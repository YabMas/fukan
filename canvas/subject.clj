(ns canvas.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, as ONE stratum of PURE GRAMMAR.

   The subject is fukan's own design: concepts, not inhabitants. So it is authored entirely as
   `defstructure` PORTRAITS — zero instances. A design model has no legitimate singleton: every fact
   about a one-of-a-kind concept (the Model, the read faculty, the synthesis faculty) is
   DEFINITIONAL, so it lives in the grammar, reflected onto the graph by `lib.grammar`. The only
   plural concept, `Source`, captures its two flavours in its `:enum`, not in two instances. The
   realization seam (`canvas.correspondence`) and the completeness laws attach to these reflected
   grammar nodes; instances appear only where something open and contingent is recorded (the act
   registry `canvas.acts`, the code-crossing seam).

   Read top-to-bottom it IS the design:
     Nodes wired by Relations ARE the graph; the Form (defstructure) builds a Vocabulary over that
       substrate — the language a model is authored in (fukan ships none; bottom-up language building);
     one hub MODEL — that graph, authored in a Vocabulary;
     two SOURCES converge IN — author (intent, design↓), extract (reality, code↑);
     a LENS reads it; a PROJECTION synthesises from it (built on the lens, not a twin)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

;; ── the floor: substrate + the grammar-building Form ───────────────────────────

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

;; ── the hub: the one graph ─────────────────────────────────────────────────────

(defstructure Model
  "The hub: one unified GRAPH — many Nodes wired by many Relations — AUTHORED IN one or more
   Vocabularies. Spec and implementation live on this one graph. Its one-ness is an apparatus
   guarantee (one substrate), not a design choice."
  {:made-of     [:* Node]
   :wired-by    [:* Relation]
   :authored-in [:* Vocabulary]})

;; ── two ORIGINS converge IN — intent vs reality (the two flavours ARE the enum) ─

(defstructure Source
  "A way IN — content converging on the Model. The in-fold is two ORIGINS, one of each `:polarity` —
   design authored DOWN (intent), code extracted UP (reality). The two flavours are the enum, not two
   instances. A Source has no focus: focus is a read-side query, and the write side has nothing to
   apply it to."
  {:into Model :polarity [:enum "design-down" "code-up"]})

;; ── the Model is USED two ways (not twins) ─────────────────────────────────────

(defstructure Lens
  "The READ act — a focus over the Model that resolves to a sub-graph. A lens is a QUERY, so it is
   intrinsically read-side; evaluating it IS the reading. The lens catalog + the gating/contract that
   make a reading a Signal vs a View live lower, in `canvas.acts`."
  {:reads Model})

(defstructure Projection
  "The SYNTHESIS act — re-presenting the Model in a target form (materialization), THROUGH a Lens. NOT
   a twin of the read: built ON the lens, doing work it does not (mapping kinds, contextualization).
   The full grammar lives lower in `canvas.acts/Projection` (same concept, two altitudes)."
  {:through Lens})
