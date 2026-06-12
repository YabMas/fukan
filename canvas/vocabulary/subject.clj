(ns canvas.vocabulary.subject
  "fukan's SUBJECT grammar — the promotion of the `demos/self` experiment into the live canvas
   as Layer 1 of the verifiable tower (Subject → Realization → Code).

   It names fukan's design directly, rather than leaving it to emerge from a graph of generic
   faculties (an earlier self-model, since removed). The shape:

     one hub MODEL, made-of one PRIMITIVE (defstructure);
     two SOURCES converge IN — author (intent, design↓) and extract (reality, code↑): two
       ORIGINS in tension;
     the Model is USED two ways — but the two are NOT twins (an earlier grammar forced them under
       one `Act` umbrella with a `finding`/`artifact` `Output`; that symmetry was false — it
       flattened a primitive and a composite into a fake pair):
       - a LENS reads it — a focus/query that resolves to a sub-graph. The lens IS the read; there
         is no separate `probe` act wrapping it.
       - a PROJECTION synthesises from it — renders THROUGH a lens into a target artifact. It is
         built ON the lens, doing work the lens does not (mapping kinds, contextualization).
     one CORRESPONDENCE: extract ⊣ project — the code-up Source and the Projection are inverse over
       the one Model, so their disagreement is checkable as drift (#4, emergent).

   This `Lens` coexists with the lower-altitude `canvas.vocabulary.act/Lens` (the lens catalog) —
   different namespaces, no collision.

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

(defstructure Lens
  "The READ act — a focus over the Model that resolves to a sub-graph. A Lens names WHICH slice to
   attend to (`:focus`) and `:reads` the Model. A lens is a QUERY, so it is intrinsically read-side
   (a query presupposes a populated graph — the write side, `Source`, has nothing to apply a focus
   to). Evaluating a lens IS the act of reading; the read needs no structure of its own on top of
   the lens — which is why there is no `Probe`. (The lens catalog — survey/patterns/drift/… — and
   the gating/contract that turns a reading into a trust Signal vs a View live lower, in
   `canvas.domain.lens` / `canvas.vocabulary.act`.)"
  {:reads Model
   :focus :String})

(defstructure Source
  "A way IN — content converging on the Model. The in-fold is two ORIGINS, one of each `:polarity` —
   design authored DOWN (intent), code extracted UP (reality). A Source has no focus: focus is a
   read-side query, and the write side has nothing to apply it to."
  {:into     Model
   :polarity [:enum "design-down" "code-up"]}
  (law "the loop closes — every code-up source is matched by a correspondence"
    (matched-by :lifts :from Correspondence :when {:polarity "code-up"})))

(defstructure Projection
  "The SYNTHESIS act — re-presenting the Model in a target form (materialization). It renders THROUGH
   a Lens (the focus) into an artifact (Blueprint → implementation specs, Docs → documentation). It
   is NOT a twin of the read act: it is built ON the lens, doing work the lens does not (mapping
   source kinds to artifacts, contextualizing one render through another). Its full composition
   grammar lives lower, in `canvas.vocabulary.act/Projection`.

   Shares its short name with that lower-altitude `Projection` — the same concept at two altitudes,
   exactly like `Lens`. ns-precise law scoping (`[?o :structure/of <qualified-tag>]`) keeps the
   lower `has-any` law from ranging over this abstract faculty, so the two coexist cleanly."
  {:through Lens})

(defstructure Correspondence
  "#4, EMERGENT — the recognition that one Source and one Projection are inverse over the one Model.
   It `:lifts` the code-up Source (extract: code → model) and `:lowers` the Projection (project:
   model → code/artifact); the two compose to the identity on the shared graph, so their
   disagreement is checkable as drift. This is the within-SUBJECT relation; the subject→code seam is
   `Realization` in `canvas.correspondence`."
  {:lifts  Source
   :lowers Projection}
  (law "a correspondence lifts a code-up source"
    (target :lifts {:polarity "code-up"})))
