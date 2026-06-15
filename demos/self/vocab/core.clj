(ns demos.self.vocab.core
  "A CANDIDATE re-grammar of fukan's own SUBJECT — an experiment in finding abstractions
   that express fukan's design directly, rather than emergently from a flat faculty-graph.

   The current self-model (`canvas/domain/faculties.clj`) describes fukan as ten `Faculty`
   nodes wired by four generic edge kinds (feeds/builds-on/supplies/reads). That grammar can
   DRAW fukan's shape but cannot NAME a single design idea in it: the two-source confluence is
   'two supplies edges you notice', the act duality is 'Lens feeds two things'. The ideas are
   emergent. This vocab makes them STRUCTURAL.

   The shape it commits to — NOT a clean in/out mirror (that was over-symmetrized); the two
   sides differ in kind, and focus lives only on the read side:

       author  (intent,  design ↓) ─┐
                                     ├─► MODEL ──Lens(focus)──┬─► probe   → finding   (analyse)
       extract (reality, code  ↑) ──┘      │                 └─► project → artifact  (synthesise)
                                           └────────── extract ⊣ project ──────────┘ = Correspondence

       #1 two ORIGINS in tension (intent↓ vs reality↑). NB the 'one graph' half is apparatus —
          one substrate is guaranteed, not designed; the subject-unique half is the two origins.
       #3 two MODES of use (analyse vs synthesise), each through a Lens. A focus is a query, and
          a query needs a populated graph — so focus is READ-side only; the write side has no
          pivot (construction is local; observing a whole needs selection). The grammar SAYS this:
          Act has `:through Lens`, Source has none.
       #4 Correspondence = the ONE real symmetry: extract ⊣ project, inverse across the model↔code
          boundary — EMERGENT, a law (references a Source AND an Act).
       #2 the Model is made-of one Primitive (defstructure) — a drill-down, not a headline.

   The in-2fold (origins) and out-2fold (modes) are both binary but are NOT mirror images. Each
   design idea is a structure or a law, so the design reads off the grammar and is CHECKABLE.
   Authored as a demo (isolated classpath) so its reused names — Model, Lens — don't collide with
   the main canvas in the single global tag registry."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

;; ── #2 the floor: what the graph is made of (drill-down, deliberately minimal) ──

(defstructure Primitive
  "What the Model is made of: `defstructure` = a composition of slots + datalog laws. The
   reflexive floor — a lower-altitude detail you drill into once the high-level shape is grasped,
   per the altitude ordering (grasp #1/#3 first, then #2)."
  {:doc [:? :string]})

;; ── the hub ─────────────────────────────────────────────────────────────────

(defstructure Model
  "The hub: one unified structure graph that everything converges on and is read from. Made-of
   one Primitive. Its one-ness is an APPARATUS guarantee (one substrate underlies every fukan
   model), not a subject design choice — so #1's headline is the two ORIGINS, not the one graph.
   (No structure for the schema sources conform to: that 'dialect' is apparatus and recedes — it
   is not a subject concept, and it is emphatically not a read-side focus.)"
  {:doc     [:? :string]
   :made-of Primitive})

;; ── the read-side focus (no write-side mirror — a focus is a query) ──────────

(defstructure Lens
  "A focus over the Model — which slice an Act attends to. A Lens is a QUERY (a selection over the
   graph), and a query presupposes a populated graph — so focus is intrinsically READ-side. There
   is deliberately NO write-side counterpart: construction is local (you author one structure at a
   time), only observing an existing whole needs selection. That the grammar gives `Act` a
   `:through Lens` but `Source` no pivot is the design stating this asymmetry, not hiding it."
  {:focus :string})

;; ── what an act yields ──────────────────────────────────────────────────────

(defstructure Output
  "What an Act yields — a `finding` (probe → a reading to reason with) or an `artifact`
   (project → a target form to build from)."
  {:doc [:? :string]})

;; ── #1 two ORIGINS in tension (not a mirror of the out-side) ─────────────────

(defstructure Source
  "A way IN — content converging on the Model. The in-2fold is two ORIGINS, one of each
   `:polarity` — design authored DOWN (intent), code extracted UP (reality). This is the
   subject-unique half of #1; the 'one graph' half is apparatus (one substrate). A Source has no
   `:through` pivot: the schema it conforms to is apparatus, and focus is a read-side query that
   the write side has nothing to apply yet."
  {:doc      [:? :string]
   :into     Model
   :polarity [:enum "design-down" "code-up"]}
  ;; #4 made assertable from THIS side: a lifted-in source must have a corresponding act to
  ;; lower it back down — else the loop can't close. Names the design claim 'correspondence
  ;; emerges where extract meets project'. (The combinator expands the negation through a
  ;; RULE, so it fires correctly even when NO correspondence exists yet — the datascript
  ;; empty-relation `not-join` gotcha is encapsulated in the kernel.)
  (law "the loop closes — every code-up source is matched by a correspondence"
    (matched-by :lifts :from Correspondence :when {:polarity "code-up"})))

;; ── #3 the OUT-dual: two complementary acts, one focus ──────────────────────

(defstructure Act
  "A way OUT — using the Model. The OUT-dual: two complementary acts, one of each `:mode` —
   probe = analyse (→ a reading), project = synthesise (→ an artifact). Each `:reads` the Model
   `:through` a Lens (the focus). Analysis vs synthesis is the duality the flat faculty-graph
   could not name."
  {:doc     [:? :string]
   :reads   Model
   :through Lens
   :mode    [:enum "analyse" "synthesise"]
   :yields  Output})

;; ── #4 emergent: the loop closes ────────────────────────────────────────────

(defstructure Correspondence
  "#4, EMERGENT — not a primitive faculty but the recognition that one Source and one Act are
   inverse over the one Model. It `:lifts` a code-up Source (extract: code → model) and
   `:lowers` a synthesise Act (project: model → code/artifact); the two compose to the identity
   on the shared graph, so their disagreement is checkable as drift. That it references BOTH a
   Source and an Act is the structural form of 'falls out of #1 and #3'."
  {:doc    [:? :string]
   :lifts  Source
   :lowers Act}
  (law "a correspondence lifts a code-up source"
    (target :lifts {:polarity "code-up"}))
  (law "a correspondence lowers a synthesise act"
    (target :lowers {:mode "synthesise"})))
