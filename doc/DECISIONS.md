# Fukan — Decisions Log

**Status:** Reasoning record for the lean kernel — *why* the substrate has the shape
[MODEL.md](./MODEL.md) describes. Companion to [VISION.md](./VISION.md) and
[DESIGN.md](./DESIGN.md).

**Supersession note.** An earlier decision trace recorded a much larger kernel —
nine primitives, thirteen relations, an Allium/Boundary spec layer, and a later
"Node + Relation, classified by tag-applications" unification. That whole arc was
**superseded** by the rebuild around a single primitive (D1). The *philosophy*
carries forward — force-and-gate (a feature earns its place only when something
forces it and its shape is uniform), grow-the-language, two-tier core/vocabulary —
but the specific primitive/relation bindings are history and are not reproduced
here. This log records the decisions that define the current system.

Supersessions resolve in place: re-opening a question means re-engaging its forces,
not removing the decision.

---

## Foundation

- **D1 — The rebuild: one primitive, `defstructure`.** Fukan was radically pruned to
  a lean kernel and rebuilt around a single structure-definition primitive: *a
  structure = its composition of slots + the datalog laws that must hold of it.* The
  structure substrate **is** the model. This supersedes the nine-primitive kernel and
  the intermediate Node+Relation+tag unification: those were carrying complexity no
  current need forced. Force: the prior substrate's expressive machinery outran any
  live use; a single composition+laws primitive expresses everything tried so far.

- **D2 — The core ships mechanics only; vocabulary is per-project.** `src/fukan/canvas/`
  ships the primitive and the ingestion/projection machinery and *no* domain
  vocabulary. Every modelling project authors its own grammar (fukan-on-fukan's lives
  in `canvas/vocab/`; demos own theirs). Force: the phase goal is to exercise the core
  by *many* models; a shipped vocabulary would bias every model toward it and there is
  no use for a shared/methodology layer yet. A shared catalog (ships-in-fukan vs
  separate) is explicitly deferred.

- **D3 — A slot is a relation OR a value with a law.** Slots whose target is a
  structure reify a relation; slots whose target is a scalar store a leaf value with
  an auto-generated type-check law. One mechanism covers both. Cardinalities are
  `one` / `optional` / `many` / `some` / `ordered`.

## Modelling mechanics

- **D4 — Value identity for nameless compound data.** `^:value` structures are
  content-deduped, anonymous, ownerless nodes (identity = content hash); datascript
  dedups them. Force: in the model nothing is *inhabited*, so a value is the canonical
  representation of a nameless structural description. Verdict from modelling
  canvas-source both ways: value-style is decisively truer for compound data —
  entity-style erases lists/records into opaque named stand-ins. Authoring verbosity
  was then closed by a `(reader fn)` that expands native data-literals
  (`Foo` / `[X]` / `{:f X}`), chosen over `#tag` literals so clj-kondo needs no config.

- **D5 — Ordered composition stores position on the relation.** An `(ordered T)` slot,
  authored with a vector, stores each element's position as `:rel/order` on its
  reified relation — chosen over value-encoding the sequence, for clean queries. Order
  enters a value's content key, so `[A B] ≠ [B A]`. Rejected on scalar slots.

- **D6 — Classification dissolved into the substrate.** There is no `:entity/type` /
  `:affordance/role` classification layer; a node's kind is just `:structure/of`, and
  laws/rules read kinds from that. The earlier "privileged refinement / subtyping"
  framing was retired: subtyping, where wanted, is ordinary modelled data, not a core
  mechanism. (Pressure-test rule: before privileging a new core mechanism, check
  whether existing primitives already express it — if so, it's ergonomics to defer.)

## Querying, verifying, projecting

- **D7 — Laws read at domain altitude (vocab-derived rules).** `check` derives datalog
  rules from the live vocabulary (kind rule per structure, relation rule per relation
  slot, fixed `in-module`/`named`) and injects them into every law's query. Force: laws
  written in raw `:structure/of` / `:rel/*` navigation are unreadable and leak the
  substrate; the vocabulary already names exactly the predicates a law wants.

- **D8 — Correspondence is its own concern, not a slot on a domain.** A domain
  structure's laws describe that domain's *own* behaviour. "Does the model realize in
  code" is orthogonal and lives in `target/correspondence`, with `:scope :global` to
  escape self-scoping. Test for placement: a law about how the MODEL realizes in CODE
  belongs to correspondence; a cross-view but model-internal law (`realized-by`) stays
  on the domain.

- **D9 — Model → code is one-directional.** Code is *projected from* the model; it
  never reads the model back. Materialize is the down-direction (`render` multimethod
  on `:structure/of`, instruction text kept out of the pure vocabulary; composition
  falls out of dispatch). The open axis — pin the implementation in the model
  (query-as-data) vs express Lens *intent* and let an implementing LLM fill detail —
  leans toward intent/contract: pinning low-level datalog drags detail upward against
  the thesis. (Self-model = canvas specs projected into fukan's code; a target-model
  would be the db of an analyzed codebase, read at runtime as input data — a different
  thing.)

- **D10 — A lens is one selection expression.** A Lens's focus query is a single
  datalog `:where` clause-vector; `evaluate-lens` yields a genuine sub-graph. Selection
  and transitive closure are recursion *within* the one query — there is no
  seed/closure split (a rejected earlier sketch). Lens **refinement**-composition
  (lens-within-lens, lens as graph→graph) is deferred in full, to be explored properly
  later — not the cheap set-algebra (union/intersect) now. `evaluate-lens` is a clean
  seam, so composition is additive when it lands.

- **D11 — Cross-module references are by-name and build-time.** Authored as
  `(across "module")` / `(across "module" "name")`, resolved post-merge. Compile-checked
  var-references would be a whole-ref-system shift; deferred.

## Direction and deferrals

- **D12 — Exercise the core by modelling; no premature middle layer.** The work is
  authoring a wide variety of models directly on `defstructure`. A reusable
  methodology/middle layer (DDD/hexagonal/C4) is **not** built now — there is no
  purpose for one. Keep the core *able* to grow one later via the refinement mechanism,
  and prove that mechanism only when a concrete need forces it.

- **D13 — The browser explorer is deferred indefinitely.** It is fukan's eventual
  vision but is not on the near roadmap; the core is exercised first. Parked under
  `.paused/`; not to be proposed as a next step.

---

*Section identifiers from the prior trace (K\*/R\*/V\*/C\*/P\*/B\*) are retired with the
architecture they described; see git history for the pre-rebuild log.*
