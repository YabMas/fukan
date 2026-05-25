# Architect Canvas — Design Decisions

**Status**: Design decisions from a multi-session design conversation (2026-05-22 through 2026-05-25). Not yet implemented. Briefs a fresh implementation session.

## Context

The architect canvas is a **thinking tool** for designing systems modelled in fukan. It is the surface through which a human (or LLM agent acting as architect) explores designs, builds the project's design vocabulary, and reasons at design altitude — above raw substrate but below ad-hoc prose.

**Primary value**: clear thinking. Fast iteration on "how should this be shaped." The architect can sketch designs that would take far longer in implementation code, and reason about composition and well-formedness at the level of designed primitives, not raw kernel terms.

**Secondary value** (deliberately not the centre): derivative utilities the canvas enables — implementation blueprint, verification plans, drift detection.

## Trajectory note

This is iteration one. Several earlier directions were explored and rejected during the design conversation:

- A three-layered stack (Datalog → substrate → free canvas) — collapsed too early.
- A new `posit` / `require` / `eval` DSL — invented vocabulary parallel to substrate.
- A `ModelHistory` substrate extension — over-reach; sessions don't need to be substrate content yet.
- A `TagDefinition` extension (the so-called "Cut A" — constructor slot) — overtaken when we committed to Clojure embedding.

The committed direction is **Clojure embedding**: the architect canvas IS Clojure code, in `.clj` files, using a small library (`fukan.canvas`) that provides the lift mechanism, ergonomic prose forms, and constraint compilation.

For this iteration, **Allium (`.allium`) and Boundary (`.boundary`) are set aside as the architect surface**. They remain as legacy targets fukan still analyses, but new design work happens in `.clj` canvas namespaces. Materialisation back to Allium / Boundary is out of scope for this iteration.

## In scope / out of scope

**In scope (iteration one):**
- A `fukan.canvas` library providing the lift mechanism, body forms, constraint vocabulary, and reads.
- A working canvas namespace expressing a real subsystem (test target: `target/clojure/`).
- Datalog name-resolution: high-level references compile to kernel attribute-schema patterns.
- Two constraint levels (composition + system) and their evaluation.

**Out of scope (deferred):**
- Session history (branching, retraction, comparison of design variants).
- Materialisation back to `.allium` / `.boundary` (or a future fukan-native text spec).
- `enacts` verification — checking that a structure actually supports the behaviour it claims to enact.
- Performance optimisation of constraint checks (run all, no incremental).
- A `Subsystem` lift distinct from `Module` — until patterns warrant.

## Committed design decisions

### Architecture

1. **Clojure embedding.** The architect canvas is `.clj` code. No new text language. Allium / Boundary remain legacy targets only.
2. **Single-surface canvas.** No layered stack, no parallel substrate. The canvas is fukan reflected on itself, with Clojure as the host.
3. **Two modes, sharply separated.**
   - *Mode B (Lift)*: declare new design primitives via `defconstructor`.
   - *Mode A (Design)*: invoke them to build up the design.
4. **Invoke-not-apply discipline.** Lifted primitives are *invoked* (they construct substrate); they are not *applied* (tagged onto existing substrate). The architect never says "make a Container, then tag it" — they say "make a Module" and the canvas produces the substrate underneath.

### The Module lift

5. **One lift covers all module shapes** — leaf modules, subsystems, value-owning, behaviour-bearing. Variation lives in which body forms are populated. Specializations (`PipelineModule`, etc.) emerge only when patterns recur enough to warrant their own name.
6. **Module identity is convention-derived.** `(namespace + module-name)` maps to substrate path. No `path` parameter — paths follow directory/namespace convention, not configuration.
7. **Dependencies are inferred from references.** Symbol references inside a Module body imply `uses` relations. No explicit `(uses …)` form. Escape hatch `(depends-on X)` may be added later if a non-referential dependency case appears.
8. **Subsystem falls out of `contains`.** A Module whose body uses `(contains a b c)` is a subsystem. `contains` accepts both top-level Module references and inline `(Module …)` forms.

### Body forms

9. **Self-identifying composition.** No `:keywords` introducing slots. Each body form announces itself by its head symbol: `description`, `scope`, `includes`, `excludes`, `note`, `value`, `operation`, `rule`, `invariant`, `constraint`, `contains`, `exports`, `enacts`.
10. **First-class prose forms.** Each form takes a name symbol and a docstring as native arguments — reads like `defn` / `defrecord`. Prose is structured data, not strings-in-maps.
11. **Universal helper forms:**
    - `(value Name "doc" (field …) …)` — owned Value type
    - `(field name :Type)` — typed slot
    - `(operation Name "doc" (params …) (returns …) (triggers …)?)` — Boundary callable
    - `(rule Name "doc" (when …))` — behavioural rule
    - `(invariant Name "doc")` — prose-only invariant (the *why*; not machine-checked)
    - `(constraint Name "doc" '[…datalog…])` — machine-checked Datalog (the *what-must-hold*)

### Constraints

12. **Two levels, named explicitly:**
    - *Composition constraints*: live on the lift (`defconstructor`'s composes-as section). Govern how instances of the lift must be shaped. The design language's grammar.
    - *System constraints*: live inline on a Module instance, or as top-level forms. Govern the specific design being built. The architect's intent about the particular system.
13. **Datalog stays as the constraint language.** No new DSL invented.
14. **Name-resolution expand step.** Inside a constraint's Datalog body, named things (lifts, modules, values, `defquery` operators) resolve to kernel attribute-schema patterns. The architect references `(Module ?x)` and the canvas substitutes `[?x :tag :fukan/Module]`. Same for instances by name and `defquery`-defined operators.
15. **`defquery` is the extension point.** Architect-defined Datalog operators register expansions; bodies are themselves Datalog (with name-resolution recursively applied). Term rewriting; no compile target to design.
16. **`this` binding** refers to the enclosing Module instance inside a constraint body.

### Behaviour placement

17. **Inline by default.** Behavioural forms (invariants, rules, constraints) live inside the Module they're coupled to. This is the common case and reads as locality.
18. **Top-level + `enacts` is the escape hatch.** For genuinely cross-cutting behaviour, define an `invariant` / `rule` / `constraint` at the top of the canvas file; reference it from Modules via `(enacts Name)`. No physical separation of behaviour and structure; the architect chooses placement per case.

### Abstract structure vs runtime realization

19. **The canvas designs abstract structure and behaviour, not runtime realization.** `operation`, `triggers`, `uses`, and `rule` describe *abstract capability and causality*, not how they are carried at runtime. The same module's operations could be realised as in-process function calls, REST endpoints, gRPC, event subscriptions on a message queue, async actor messages, etc. The canvas is realization-neutral.

    Architects (human or LLM) reading or writing the canvas must NOT infer in-process function-call semantics from `(operation … (params …) (returns …))`. It is a capability declaration — inputs and outputs, with signature — agnostic about the carrier.

    How realization is eventually expressed in the canvas (whether as an overlay, a separate file, an annotation, or something else) is **a separate design conversation deferred to a future iteration**. Iteration one does not commit to a shape; it only commits to the principle that the abstract design must be re-realizable without being rewritten.

## The library surface

`fukan.canvas` exports:

```
;; Lift declaration
defconstructor             ; declare a new lift
defquery                   ; declare a Datalog-expanding operator

;; Body forms (each macro; identity from form head)
description scope includes excludes note
value field operation rule invariant constraint
contains exports enacts depends-on

;; Substrate-construction helpers (called from a :produces body)
container boundary behaviour intent
edge apply-tag

;; Reads (wrap fukan.agent.api)
primitives relations neighborhood violations q

;; Lift-aware readings
lift-economy lift-coverage lift-residue lift-density lift-composition-depth

;; Constraint check
check                      ; run all composition + system constraints
```

## The architect surface — example shape

```clojure
(ns fukan.target.clojure.canvas
  (:require [fukan.canvas :as fc
             :refer [Module value field operation rule invariant constraint
                     enacts contains exports description scope excludes note]]
            [fukan.target.clojure.address :refer [CanonicalAddress]]
            [fukan.project_layer.registry :refer [Registry]]))

;; Top-level behaviour for cross-cutting reuse (escape hatch)
(invariant Deterministic
  "Outputs are stable across invocations on the same inputs.")

;; A leaf Module — behaviour inline (default case)
(Module source
  (description
    "Source walker and top-level form reader for the Clojure target.")
  (scope
    "Clojure source walking and top-level symbol extraction — the
     read-only catalogue the Analyzer indexes by canonical address.")
  (excludes "Address resolution (see address)"
            "Type translation (see types)")
  (note
    "This module necessarily references Clojure-specific concepts (top-
     level forms, the public/private definition distinction) — it is
     the language-specific seam between the kernel-agnostic Model and
     the Clojure source tree.")

  (value SourceSymbol
    "One top-level definition discovered in a Clojure source file…"
    (field ns :String) (field name :String)
    (field kind :String) (field file :String))

  (operation find_clj_files
    "Walk a root directory and return absolute paths to all
     discoverable Clojure source files in a deterministic order."
    (params [root :String]) (returns [:list :String]))

  (operation extract_symbols
    "Read a Clojure source file and return one SourceSymbol record for
     every top-level definition discovered."
    (params [path :String]) (returns [:list :SourceSymbol]))

  (invariant ReadOnly
    "Source walking never mutates the source tree, never evaluates
     the forms it reads…")

  (invariant NoEval
    "Forms are read as data only. The reader is configured with
     read-eval disabled…")

  (enacts Deterministic)

  (constraint internal-to-clojure-target
    "source's operations are callable only from sibling Clojure
     target modules."
    '[:find ?caller ?op
      :where (Module ?caller)
             (boundary-operation this ?op)
             (uses-call ?caller ?op)
             (not (in-subsystem ?caller clojure))]))

;; A subsystem — Module with `contains`
(Module clojure
  (description "Clojure target subsystem.")
  (contains source address types blueprint projector analyzer)
  (exports blueprint/Blueprint source/SourceSymbol))
```

## Constraint compilation

Inside the Datalog body of a `constraint` form, the canvas runs a term-rewriting pass. Symbol references resolve to kernel patterns:

| Form in query | Origin | Expands to (kernel Datalog) |
|---|---|---|
| `(Module ?x)` | lift name | `[?x :tag :fukan/Module]` |
| `(SourceSymbol ?x)` | value name | `[?x :tag :value/SourceSymbol]` |
| `source`, `clojure` | named Module bound as entity id | `?source-id`, `?clojure-id` |
| `this` | enclosing instance | bound entity id of the current Module |
| `(uses-call ?a ?op)` | library-shipped relation | `[?a :module/calls ?op]` (or equivalent) |
| `(boundary-operation ?m ?op)` | library-shipped relation | `[?m :container/boundary ?b] [?b :boundary/operation ?op]` |
| `(in-subsystem ?m ?s)` | architect-defined via `defquery` | recursively expanded query body |

The canvas ships a small starter set of relation expansions. `defquery` lets the architect extend it with project-specific terms.

## Reading semantics

`fc/check` runs every composition and system constraint over the current model and returns:

```
[{:lift Module :constraint internal-to-clojure-target
  :message "…" :offenders [{:caller … :op …}]}
 …]
```

Lift-aware readings:
- `lift-economy` — map of `{lift-name instance-count}`. Earned vocab is high.
- `lift-coverage` — ratio of primitives covered by some lift to total.
- `lift-residue` — primitives carrying no lift-tag (substrate without language; decoration risk).
- `lift-density` — lift-tagged primitives / total primitives.
- `lift-composition-depth` — deepest constructor-invocation chain (emergent layering).

## Deferred / open questions for future iterations

1. **`defconstructor`'s shape.** Current sketch was `(defconstructor Name doc params :accepts … :produces … :composes-as …)`. The section layout isn't settled. Propose a clean form before committing.
2. **`enacts` verification.** Does a Module that declares `(enacts Deterministic)` actually expose the operations that invariant needs to apply to? Currently unchecked. Worth designing once iteration one is running.
3. **Session history.** Branching, retraction, comparison of design variants. Out for now.
4. **Recurring system constraints.** Patterns like "every subsystem must export at least one Value" might warrant project-level constraint templates. Wait for evidence.
5. **Persistence story.** Canvas namespaces are `.clj` files. Where in the project tree? Convention probably mirrors substrate path (e.g., `src/fukan/target/clojure/canvas.clj` for `target/clojure/`). Confirm in implementation.
6. **Runtime realization.** Per decision 19, the canvas designs abstract structure; how operations are carried at runtime (in-process function, REST, gRPC, message queue, event subscription, etc.) is a separate concern. The shape of how realization will be expressed in the canvas — overlay, separate file, annotation, or some yet-undiscussed alternative — is deferred to its own design conversation. The only commitment iteration one makes is that the abstract design must be re-realizable without being rewritten.

## Implementation guidance

When picking this up cold:

1. Start with `fukan.canvas` library skeleton. Get `defconstructor` plus body forms compiling, even if `:produces` is a stub.
2. Build the substrate-construction helpers (`container`, `boundary`, etc.) on top of fukan's existing model API.
3. Implement Datalog name-resolution as a term-rewriter over query expressions.
4. Implement `check` calling into fukan's `q`.
5. Test by expressing the `target/clojure/` subsystem fully in the canvas. Compare ergonomics to the existing `.allium` / `.boundary` files. The seven modules — `address`, `source`, `types`, `blueprint`, `projector`, `analyzer`, plus the `clojure` subsystem — are the natural test material.

## Background context

This design emerged from a multi-session conversation involving the BASHES dialectic collective. Key insights that shaped it:

- The substrate's `TagDefinition` mechanism was designed as the methodology extension point. Sessions / canvas content are a methodology.
- "Two ontologies bridged at materialisation" is the Allium / Boundary lateral-glue problem reborn. Avoid it.
- Composition constraints and system constraints serve different purposes; both must be first-class.
- Datalog already works; don't replace it. Make it ergonomic via name resolution.
- The architect's primary value is clear thinking, not derivative outputs. Derivatives (blueprint, verification, drift) emerge from a model expressed at design altitude.

## References

- `doc/MODEL.md` — substrate authoritative
- `doc/DESIGN.md` — design principles
- `doc/VISION.md` — project vision
- `src/fukan/model/spec.allium` — substrate spec (TagDefinition lines 850-905, Expression lines 214-296)
- `src/fukan/target/clojure/` — the target subsystem this canvas iteration uses as its first test case
- `AGENTS.md` — agent-level API surface (fukan's read-side, the canvas's library wraps this)
