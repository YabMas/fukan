(ns canvas.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, as ONE stratum of PURE GRAMMAR.

   The subject is fukan's own design: concepts, not inhabitants. So it is authored entirely as
   `defstructure` PORTRAITS — zero instances. Every fact about a one-of-a-kind concept is
   DEFINITIONAL, so it lives in the grammar, reflected onto the graph by `lib.grammar`. The
   realization seam (`canvas.correspondence`) and the completeness laws attach to these reflected
   grammar nodes.

   Read top-to-bottom it IS the design:
     SUBSTRATE — Nodes wired by directed, kinded Relations ARE the graph.
     GRAMMAR — the language you build over the substrate. The one Form (`defstructure`) produces
       a Structure; a Structure is a composition of Slots plus the Laws it asserts — AND it is the
       one notion of SHAPE (a shape is a Structure: record, type, Kind, Schema all collapse into
       it; cardinality lives on the Slot; a sum is a Structure with `:refines`-members). A
       Vocabulary is the set of Structures you define — the language a model is authored in (fukan
       ships none; bottom-up language building).
     MODEL — the hub: that one graph, authored in Vocabularies.
     IN — one Source, two flavours (its `:enum`): design↓ (intent), code↑ (reality).
     OUT — a Lens reads it; a Projection synthesises from it (built on the lens, not a twin)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

(declare Structure Slot Law)

;; ── the floor: substrate ───────────────────────────────────────────────────────

(defstructure Node
  "The atom of the graph — an instance OF a `Structure` (every node is typed by the structure it
   inhabits, mirroring the substrate's `:structure/of`). Whether a node is identified by name or by
   content is an identity-assignment detail of the realization, not part of the domain shape."
  {:of Structure})

(defstructure Relation
  "A directed, kinded edge: it wires a `:from` Node to a `:to` Node under a `:kind` (the
   relation's name/role). Nodes + Relations ARE the graph; everything else is built over them.
   Order and label are realization bookkeeping (datascript `:rel/order`, `:rel/label`) and stay
   OUT of the domain shape — they live on the realized kernel `Relation` Kind, and the gap
   (domain shape ⊂ realized shape) is exactly what the correspondence seam can check."
  {:from Node :to Node :kind :string})

;; ── the grammar: the language you build over the substrate ───────────────────────

(defstructure Law
  "A constraint a `Structure` asserts — a datalog constraint that must hold of the model,
   serialised as its `:where` clause or a combinator expansion. A Law is OWNED by the Structure
   that asserts it (ownership-on-owner), so no back-reference to the Structure is modelled here."
  {:datalog :string})

(defstructure Slot
  "A typed relation-template — one field of a `Structure`. `:typed-by` names the target
   `Structure`; `:cardinality` is the quantifier (matching the kernel's cardinality atoms). A Slot
   is a template; its instances are `Relation`s (kind = the slot's name). `:label` is the optional
   per-target authoring label — present only on labelled targets in a plural slot (`[label target]`
   pairs, e.g. map-of keys). Cardinality SUBSUMES the collection type-algebra — a list/set/map-of is
   a plural slot, not a type."
  {:typed-by    Structure
   :cardinality [:enum "one" "optional" "many" "some" "set"]
   :label       [:? :string]})

(defstructure Structure
  "THE grammar unit — AND the shape. A named composition of the Slots it `:composes` plus the
   Laws it `:asserts`. This one concept subsumes record / type / Kind / Schema: a shape IS a
   Structure. A `^:value` (anonymous) Structure is a nameless shape; a slot-less Structure is a
   primitive (a scalar leaf). A Structure may `:refines` a parent — that is how a SUM is
   expressed: the parent is the union (sum) of the Structures refining it (the classification
   mechanism; a member's executable form is its `realized-as` datalog)."
  {:composes [:* Slot]
   :asserts  [:* Law]
   :refines  [:? Structure]})

(defstructure Form
  "`defstructure` — the one grammar-building form fukan ships; everything else is grown with it
   (bottom-up language building). Working over the substrate, a Form application `:produces` a
   `Structure` (which types a Node and composes Relations)."
  {:produces Structure})

(defstructure Vocabulary
  "A grammar built with the Form — the set of Structures it `:defines`. The language a Model is
   AUTHORED IN: what a model can say is whatever its vocabularies define."
  {:defines [:* Structure]})

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
