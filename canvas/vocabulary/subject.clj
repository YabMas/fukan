(ns canvas.vocabulary.subject
  "fukan's SUBJECT grammar — the promotion of the `demos/self` experiment into the live canvas
   as Layer 1 of the verifiable tower (Subject → Realization → Code).

   It names fukan's design directly, rather than emergently from the ten-node `Faculty` graph in
   `canvas.domain.faculties` (which it is intended to REPLACE over time; the two coexist during the
   migration). The shape, settled in the demo:

     one hub MODEL, made-of one PRIMITIVE (defstructure);
     two SOURCES — author (intent, design↓) and extract (reality, code↑): two ORIGINS in tension;
     two ACTS — probe (analyse) and project (synthesise) — read the Model THROUGH a LENS (focus),
       each yielding an OUTPUT;
     one CORRESPONDENCE: extract ⊣ project, the one inverse pair across the model↔code boundary.

   The read-side focus (`Lens`) rejoined once tags became ns-qualified: this `Lens` coexists with
   the lower-altitude `canvas.vocabulary.act/Lens` (the lens catalog) — different namespaces, no
   collision. (A focus is a query and queries need a populated graph → focus is read-side only:
   `Act` has `:through Lens`, `Source` has no pivot.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

(defstructure Primitive
  "What the Model is made of: `defstructure` = a composition of slots + datalog laws. The reflexive
   floor (#2) — a drill-down, grasped after the high-level shape.")

(defstructure Model
  "The hub: one unified structure graph everything converges on and is read from. Made-of one
   Primitive. Its one-ness is an apparatus guarantee (one substrate), not a design choice — so #1's
   headline is the two ORIGINS, not the one graph."
  {:made-of Primitive})

(defstructure Output
  "What an Act yields — a `finding` (probe → a reading to reason with) or an `artifact`
   (project → a target form to build from).")

(defstructure Lens
  "The read-side focus — which slice of the Model an Act attends to. A Lens is a QUERY (a
   selection over the graph), and a query presupposes a populated graph — so focus is intrinsically
   READ-side; there is no write-side counterpart. (The high-altitude focus concept; the specific
   lens catalog — survey/patterns/drift/… — lives lower, in `canvas.domain.lens`.)"
  {:focus :String})

(defstructure Source
  "A way IN — content converging on the Model. The in-2fold is two ORIGINS, one of each
   `:polarity` — design authored DOWN (intent), code extracted UP (reality). No focus pivot: focus
   is a read-side query the write side has nothing to apply yet."
  {:into     Model
   :polarity [:enum "design-down" "code-up"]}
  (law "the loop closes — every code-up source is matched by a correspondence"
    :rules '[[(corresponded ?src)
              [?c :structure/of :canvas.vocabulary.subject/Correspondence]
              [?l :rel/from ?c] [?l :rel/kind :lifts] [?l :rel/to ?src]]]
    :offenders '[?src]
    :where '[[?src :val/polarity "code-up"]
             (not (corresponded ?src))]))

(defstructure Act
  "A way OUT — using the Model. The out-2fold is two MODES, one each — probe = analyse (→ a
   reading), project = synthesise (→ an artifact). Each `:reads` the Model `:through` a Lens (the
   focus) and `:yields` an Output."
  {:reads   Model
   :through Lens
   :mode    [:enum "analyse" "synthesise"]
   :yields  Output})

(defstructure Correspondence
  "#4, EMERGENT — the recognition that one Source and one Act are inverse over the one Model. It
   `:lifts` a code-up Source (extract: code → model) and `:lowers` a synthesise Act (project: model
   → code/artifact); the two compose to the identity on the shared graph, so their disagreement is
   checkable as drift. This is the within-SUBJECT relation; the subject→code seam is `Realization`
   in `canvas.correspondence`."
  {:lifts  Source
   :lowers Act}
  (law "a correspondence lifts a code-up source"
    :offenders '[?c]
    :where '[[?l :rel/from ?c] [?l :rel/kind :lifts] [?l :rel/to ?s]
             (not [?s :val/polarity "code-up"])])
  (law "a correspondence lowers a synthesise act"
    :offenders '[?c]
    :where '[[?l :rel/from ?c] [?l :rel/kind :lowers] [?l :rel/to ?a]
             (not [?a :val/mode "synthesise"])]))
