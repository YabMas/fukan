(ns canvas.subject
  "fukan's SUBJECT — Layer 1 of the verifiable tower, as ONE stratum of PURE GRAMMAR.

   The subject is fukan's own design: concepts, not inhabitants. So it is authored entirely as
   `defstructure` PORTRAITS — zero instances. Every fact about a one-of-a-kind concept is
   DEFINITIONAL, so it lives in the grammar, reflected onto the graph by `lib.grammar`. The system
   overview's role-derived faculty→module map and the well-formedness laws below attach to these reflected
   grammar nodes.

   Read top-to-bottom it IS the design:
     SUBSTRATE — a Node is an instance of a Structure; Relations wire Nodes; a GRAPH is Nodes wired
       by Relations — the central type.
     GRAMMAR — the language you build over the substrate. The one Form (`defstructure`) produces a
       Structure; a Structure is a composition of Slots plus the Laws it asserts — AND it is the one
       notion of SHAPE (a shape is a Structure: record, type, Kind, Schema collapse into it;
       cardinality lives on the Slot; a sum is a Structure with `:refines`-members). A Vocabulary is
       the set of Structures you define — the language a model is authored in (fukan ships none;
       bottom-up language building).
     MODEL — the hub: a Graph (`:structured-as`) authored in one or more Vocabularies.
     IN — one Source, two flavours (its `:enum`): design↓ (intent), code↑ (reality).
     OUT — a Lens reads a Graph and yields a (sub-)Graph; a Projection works on a Graph (optionally
       through a Lens) and yields a ProjectionTarget."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

(declare Structure Slot Law)

;; ── the floor: substrate ───────────────────────────────────────────────────────

(defstructure Node
  "The atom of the graph — an instance OF a `Structure` (every node is typed by the structure it
   inhabits, mirroring the substrate's `:structure/of`). Whether a node is identified by name or by
   content is an identity-assignment detail of the realization, not part of the domain shape."
  {:of Structure}
  ;; CONFORMANCE (well-formedness, M2 — over the reflected grammar): the reflexive form of `:of`.
  ;; Every node must be typed by a Structure the grammar actually DEFINES (a reflected
  ;; `:lib.grammar/Structure` whose `:val/tag` matches the node's `:structure/of`). The
  ;; `[?g …]` guard makes the law inert until reflection is present, so a bespoke (un-reflected)
  ;; db checks clean — the law only bites once a grammar exists to conform to.
  (law "every node is an instance of a Structure the grammar defines"
    :scope :global
    :offenders '[?i]
    :rules '[[(grammar-typed ?i)
              [?i :structure/of ?t]
              [(clojure.core/str ?t) ?ts]
              [?s :structure/of :lib.grammar/Structure]
              [?s :val/tag ?ts]]]
    :where '[[?i :structure/of ?t]
             [?g :structure/of :lib.grammar/Structure]   ; reflection is active
             (not (grammar-typed ?i))]))

(defstructure Relation
  "A directed, kinded edge: it wires a `:from` Node to a `:to` Node under a `:kind` (the
   relation's name/role). Nodes + Relations ARE the graph; everything else is built over them.
   Order and label are realization bookkeeping (datascript `:rel/order`, `:rel/label`) and stay
   OUT of the domain shape — they live on the realized kernel `Relation` Kind, and the gap
   (domain shape ⊂ realized shape) is exactly the kind of gap a shape-level correspondence could
   target (deferred — no shape correspondence is checked today)."
  {:from Node :to Node :kind :string})

(defstructure Graph
  "Nodes wired by Relations — the central type. Everything fukan reasons over is a Graph (or a
   sub-graph of one): the Model is one, a Lens yields one, a Projection works on one."
  {:made-of  [:* Node]
   :wired-by [:* Relation]})

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
   mechanism). Refinement realizes TWO ways: DECLARED — a structural member→parent edge (the
   kernel's `:includes`) — or DERIVED — a `realized-as` membership predicate that names the parent
   inside its datalog (the member's executable form). The declared half is a graph edge; the derived
   half is a rule."
  {:composes [:* Slot]
   :asserts  [:* Law]
   :refines  [:? Structure]}
  ;; ACYCLICITY (well-formedness, M2): DECLARED refinement is a HIERARCHY, so the `:includes` graph
  ;; must be well-founded — no Structure may refine itself, directly or transitively. Ranges over the
  ;; reflected `:includes` edges (the structural, member→parent half of `:refines`). This is the one
  ;; well-formedness property the apparatus does NOT discharge by construction: a cycle is authorable
  ;; (`(includes B)` on A, `(includes A)` on B), so this law genuinely bites.
  ;; Out of scope BY DESIGN: the DERIVED half (`realized-as`), whose parent ref lives inside arbitrary
  ;; datalog (no clean edge to walk). A derived cycle is a recursive-derivation pathology, not a static
  ;; structural one — a kernel rule-cycle guard's job, not this law's.
  (law "the refinement graph is acyclic — no Structure refines itself"
    :scope :global
    :offenders '[?s]
    :rules '[[(refines+ ?a ?b)
              [?r :rel/kind :includes] [?r :rel/from ?a] [?r :rel/to ?b]]
             [(refines+ ?a ?b)
              [?r :rel/kind :includes] [?r :rel/from ?a] [?r :rel/to ?m]
              (refines+ ?m ?b)]]
    :where '[[?s :structure/of :lib.grammar/Structure]
             (refines+ ?s ?s)]))

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
  "The hub: a `Graph` (`:structured-as`) AUTHORED IN one or more Vocabularies. Spec and
   implementation live on this one graph; its one-ness is an apparatus guarantee, not a design
   choice. (The Model takes the FORM of a Graph — `:structured-as`, not a taxonomic 'is-a'.)"
  {:structured-as Graph
   :authored-in   [:* Vocabulary]})

;; ── two ORIGINS converge IN — intent vs reality (the two flavours ARE the enum) ─

(defstructure Source
  "A way IN — content converging on the Model. The in-fold is two ORIGINS, one of each `:polarity` —
   design authored DOWN (intent), code extracted UP (reality). The two flavours are the enum, not two
   instances. A Source has no focus: focus is a read-side query, and the write side has nothing to
   apply it to."
  {:into Model :polarity [:enum "design-down" "code-up"]})

;; ── the Model is USED two ways (not twins) ─────────────────────────────────────

(defstructure ProjectionTarget
  "What a `Projection` yields — a target representation the Model is rendered into (code, docs,
   instructions, …). The concrete targets are catalog instances in `canvas.instruments.projections`
   (Blueprint, DriftClose); here it is the abstract output concept.")

(defstructure Lens
  "The READ act — a focus over a Graph that yields a (sub-)Graph. A lens is a QUERY, so it is
   intrinsically read-side; evaluating it IS the reading. Graph → Graph. The lens catalog lives
   lower, under `canvas/instruments/lenses.clj` (grammar in `lib.lens`). GATING is NOT a read act:
   checking is the law/correspondence substrate (`check`), kept apart from the Lens."
  {:reads  Graph
   :yields Graph})

(defstructure Projection
  "The SYNTHESIS act — works ON a Graph and YIELDS a ProjectionTarget (Graph → ProjectionTarget).
   It near-always focuses `:through` a Lens first, but that is inessential — it operates on a Graph
   however that graph was obtained, so `:through` is optional. The full grammar (mappings,
   contextualization) lives lower in `lib.lens/Projection` (same concept, two altitudes)."
  {:on      Graph
   :through [:? Lens]
   :yields  ProjectionTarget})
