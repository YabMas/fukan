# Fukan

*Fukan* (俯瞰) — to take in a whole landscape from above.

As LLMs write more of the low-level code, the work that stays human moves up — to the
boundaries, the invariants, the architectural shape of a system. Fukan is a place to
hold and reason about that shape — the **composition of a system's concepts and the
laws that must hold of them** — together with an LLM, at that altitude.

Its one thesis: **specification and implementation live on the same graph.** Intended
structure and actual structure are not two artifacts kept in sync by discipline — they
are one graph whose consistency a machine checks. You **define** a system's structure,
**model** abstractions over it, **verify** the whole by running its laws, and **act**
on it — probing what it is, projecting it toward an implementation.

The approach is **bottom-up language building, top-down design** — the Lisp tradition
of stratified languages: grow the vocabulary the domain wants from one primitive up,
press the design down onto it as laws, and let the graph hold both — and the
LLM-written implementation — to account.

> **Status: lean-kernel + modelling-exploration phase.** Fukan was radically pruned and
> rebuilt around a single primitive, `defstructure`. The interactive browser explorer
> that gives fukan its name — the whole graph rendered as a navigable bird's-eye view —
> is **deferred indefinitely** (parked under `.paused/`); today fukan is a
> REPL-and-canvas tool, exercised by modelling. See [doc/VISION.md](doc/VISION.md) for
> the why and [CLAUDE.md](CLAUDE.md) for the architecture.

## Everything is a structure

A `defstructure` declares a structure as a **composition of slots** plus the **datalog
laws** that must hold of it — and that is the *only* primitive. The substrate **is**
the model: no separate model-map, no privileged kinds. The core ships this primitive
and the ingestion/projection machinery and **no domain vocabulary** — every project
authors its own grammar on the core.

```clojure
(require '[fukan.canvas.core.structure :as s :refer [defstructure]]
         '[fukan.canvas.core.assemble :as a]
         '[fukan.common.typing.malli])    ;; opt into the malli scalar type dialect

;; a tiny vocabulary: one structure — its slots as one typed map — plus one law
(defstructure Task
  "A unit of work that may depend on other tasks."
  {:done? :boolean
   :deps  [:* Task]}
  (law "a task cannot depend on itself"
    {:offenders [?t]
     :where [[?r :rel/from ?t] [?r :rel/kind :deps] [?r :rel/to ?t]]}))

;; a model authored against it — the instance form mirrors defstructure:
;; a name symbol (the var AND the entity name) + one {slot → value} map
(Task spec  {:done? true})
(Task build {:done? false :deps [spec]})

(s/check (a/assemble-vars [#'spec #'build]))   ;; => []  (every law holds)
```

Cardinality is a quantifier: a bare target is *one*, `[:? T]` optional, `[:* T]` zero
or more (ordered), `[:+ T]` one or more, `[:set T]` unordered. A scalar slot
(`:boolean`) stores a leaf value with an auto type-check law; a refined scalar
(`[:enum "a" "b"]`, `[:int {:min 1}]`) is checked through a pluggable type dialect
(malli ships); a slot whose target is another structure reifies a *queryable
relation*; `^:value` structures are content-deduped anonymous nodes for nameless
compound data. The model is a Cozo db, so a human or an LLM interrogates it
with the **same datalog** — fukan is REPL-native and agent-native by construction.
And the grammar itself is reflected onto the graph: vocabularies are data too, and
`(grammar)` renders the live language reference back as the very forms above — the
print-dual of authoring.

## One graph spanning spec and code

This is what the single graph buys. Fukan **extracts** your real code into the same
substrate — its Clojure extractor reads clj-kondo analysis into a FACT theory (`Ns` /
`Fn`: namespaces, functions, signatures, effects, the actual call graph, privacy) —
and merges it onto the design graph. The design↔code link is then declared as a
**morphism**, one line per design element, from outside the design vocabulary:

```clojure
;; design Module ↦ code namespace — the twin ROOT, paired by name
(correspond Module :eq Ns (bridge :qualified-suffix))

;; design Operation ↦ the PUBLIC sub-sort of extracted functions, plus relation maps:
;; declared delegation ⊑ the public call graph; declared effects ⊒ everything reached
(correspond Operation :eq [Fn :public]
  (:delegates :sub :public-call)
  (:performs  :sup [:cat [:* :calls] :performs]))
```

Every demand law is **generated** from that declaration — totality (every modelled
Operation has a realizing public function), surjectivity (every public function is
modelled: the encapsulation gap), signature adherence, call-realization, effect
coverage — each with a stable key a worklist reader addresses. Run
`(structure/check db)` and **drift surfaces as law violations** — a modelled
capability with no implementation, on the same footing as any other broken
invariant. Laws read in the vocabulary's own terms — `(Operation ?s)`, `(within …)`,
`(delegates …)` — because the core derives those datalog rules from the live
vocabulary; the recurring law shapes have **combinators** — `(law "…" (matched-by R
:from S))`, `(has R)`, `(at-most-one R)` — so common constraints are one
declarative line.

## The model talks back in its own language

Everything on the graph prints back as the forms it was authored in — the
**print-duals**. `(grammar)` renders every vocabulary live as its `defstructure`
source; `(show 'name)` renders a node as its authored instance form; `(focus '[…])`
renders a datalog-selected slice — the textual model explorer; `(check)` quotes each
offender as its form; `(correspondence)` prints the design↔fact seam as one card,
the twin ladder and every generated demand with its stable law key. Selection is
model-native datalog (binding `?n`, evaluated with the vocab-derived rules), so a
slice is selected in the same language the laws are written in.

The **act grammar** — `Lens` (a named, shared focus), `Projection` (a re-presentation
of the model from a focus), `Check` (a gate over a lens) — is the kernel's own
vocabulary for acts on the graph, and the seam for the deferred *downward* projection
(materializing the model toward implementation specs, cut 2026-07-15 to focus scope
on verified modelling). It stays live for inspection: the print-duals resolve their
focus through it.

Define → model → verify → inspect, all on one graph that holds both what the system
is meant to be and what it actually is.

## The self-model and the shipped vocabulary tier

Fukan is exercised by modelling — including **modelling itself**. `canvas/architecture/`
models fukan as a *built* system: one self-spec per `src/` module, grouped by area and
clustered into five subsystems (`canvas/architecture/subsystems.clj`) with a declared
`:may-depend` DAG that the vocabulary's architecture laws enforce against the actual
extracted code graph. Spec files under the configured spec-dirs (`*spec-dirs*`, default
`["canvas"]`) are auto-discovered and assembled into one structure db — the model.

The reusable vocabulary is the shipped **`fukan.common` tier** (`common/`, its own
classpath root outside `src/`): the structural primitives (`fukan.common.vocab.grouping`),
the code grammar by element (`fukan.common.vocab.code.*` — Kind / Effect / Operation /
Module / Subsystem, where a Module is one code namespace), a pattern tier above them
(`fukan.common.vocab.patterns.*` — PlugPoint), the malli type dialect
(`fukan.common.typing.malli`), and the Clojure extraction seam
(`fukan.common.extraction.*` — the `Ns`/`Fn` fact theory + the correspond declarations).
It loads by *require* (the `fukan.common` index), not discovery, so it contributes
grammar only when a project opts in: a consuming project depends on fukan, requires the
vocabulary, binds `*spec-dirs*` to its own spec directory, and models against the same
grammar.

## Development

Requires Clojure CLI (`clj`) and [clj-kondo](https://github.com/clj-kondo/clj-kondo) on
PATH.

```bash
clj -M:nrepl            # start an nREPL (port 7889)
clj -M:test             # run the test suite
clj -M:lint --lint src common canvas test   # the canonical clj-kondo lint
clj -M:kondo            # regenerate the defstructure lint hooks after adding/removing a structure
```

In the REPL (`clj -M:dev`):

```clojure
(go)        ; build the model (canvas specs + the Clojure extractor over src/)
(refresh)   ; reload changed code + rebuild
(status)    ; model state
(architecture)    ; the projected system map — subsystems, modules, the :may-depend DAG
(grammar)   ; the live language primer — every vocabulary rendered back as source
(correspondence)  ; the design↔fact seam as one card, every demand with its law key
(drift)     ; modelled capabilities not yet realized in code
(check)     ; every law's violations, offenders quoted as their authored forms
```

## Project structure

```
common/               the shipped fukan.common tier: the vocabulary (grouping, code/*,
                      patterns/*), the malli type dialect, the Clojure extraction seam
canvas/architecture/  fukan's self-model: per-module specs + subsystems + the :may-depend DAG
src/fukan/
  canvas/core/        the defstructure primitive, derived rules, typing plug-point,
                      the act grammar + lens evaluation, grammar reflection
  canvas/ingestion/   spec discovery + assembly (*spec-dirs*)
  canvas/projection/  the print-duals (grammar / instance / architecture)
  cozo/               the query engine: datalog→CozoScript compiler, law engine, build, mirror
  model/              build pipeline + the extraction plug-point
  infra/              model lifecycle + composition root
.paused/              the browser viewer stack (deferred indefinitely)
.legacy-allium/       pre-canvas Allium/Boundary specs (read-only baseline)
doc/                  the vision, theory, design, substrate spec, and decision trace
```

## License

TBD
