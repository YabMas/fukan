# Canvas-First Fukan — Vision Sharpening and Substrate Redesign

**Status**: Vision sharpening and substrate redesign from a design conversation on 2026-05-25, building on (and partially superseding) `2026-05-25-architect-canvas.md`. Not yet implemented. Briefs a fresh implementation session.

## Context

This document is bigger than refinement. Working through three threads parked from iteration one — naming, lift mechanism, constraint language — revealed that the substrate (with `Boundary fn` as a primitive) carried architectural assumptions the canvas direction explicitly rejects. Following the implications through forced a restatement of how fukan's parts relate.

The *purpose* of fukan is unchanged: analyse codebases to build a unified structural model that humans (and LLM agents) can explore and reason over. What shifts is the *architecture of how that purpose is delivered*:

- The canvas moves from a thinking surface *on top of* the substrate, to the *primary design surface* that the substrate exists to serve.
- The substrate moves from an opinionated model carrying architectural primitives (Boundary, fn) to a deliberately minimal kernel that ships zero architectural vocabulary.
- Architectural knowledge moves from substrate primitives + spec languages (`.allium`, `.boundary`) to canvas-level lift libraries (`fukan.canvas.cqrs`, `fukan.canvas.monolith`, etc.).
- Legacy spec languages are explicitly demoted to analysis targets.

The cost of substrate revision only grows over time. Doing it now, in tandem with the canvas direction, lets both shape each other.

## Vision sharpening

### What's preserved

- Fukan analyses codebases — implementation code and behavioural specifications — to build a unified structural model.
- Specs and implementation project onto the same model, so intended structure and actual structure appear together.
- The interactive graph is the rendering target.
- LLM-architect collaboration at the level of design vocabulary, not raw code, is a goal.
- The functional core / imperative shell architecture, enforced by structural specifications, remains.

### Four moves

**1. Canvas as primary design surface.** The canvas is no longer a thinking tool layered on top of an existing model — it is *the* surface through which the project's design is authored. Code analyzers project onto the same substrate the canvas writes to, so the explorer continues to show implementation alongside design intent. But new design work happens at canvas altitude, in canvas vocabulary.

**2. Substrate as minimal architecture-neutral kernel.** The substrate ships six primitives and zero architectural vocabulary. It carries no opinions about function calls vs events vs messages, no built-in role taxonomy, no shipped relation kinds. It provides slots; libraries fill them.

**3. Lift libraries carry architecture.** Architectural vocabulary — `command`, `query`, `event`, `function`, `topic`, `actor` — lives in lift libraries built on top of the substrate. Methodology libraries (CQRS, monolith, actor model, etc.) are first-class fukan citizens, distributed alongside or separately from the core. Projects pick a library or define their own.

**4. Legacy specs demoted to analysis targets.** `.allium` and `.boundary` remain readable for as long as they're useful for analysing existing codebases, but they are no longer the design surface fukan is co-designed with. The vision is canvas-first; if the canvas direction proves out, future work happens at canvas level regardless.

### Restated, in one sentence

**Fukan is a canvas-driven structural exploration tool, with a deliberately minimal architecture-neutral kernel that lift libraries extend with architectural vocabulary for human and LLM architects to think with.**

## Substrate redesign — structural consequence of the vision shift

The substrate must be architecture-neutral for the canvas-first vision to hold. Today's `Boundary fn` primitive bakes in function-call architecture; it has to go. What replaces it, from first principles:

### Principles

1. **Architecture-neutral.** No primitive presupposes a kind of interaction (call, message, signal, broadcast). All architecture-specific kinds emerge from lifts only.
2. **Lift-friendly.** Lifts produce substrate via helpers; primitives compose freely.
3. **Constraint-friendly.** Datalog queries it; relations and attributes are straightforwardly indexable.
4. **Extensible at well-defined points.** Tags and relation kinds are open-ended. Primitives are closed.
5. **Minimal.** Each primitive earns its keep. If a thing can be expressed via other primitives, it doesn't get its own.

### Six primitives

| Primitive | Universal because | Role in the model |
|---|---|---|
| **Module** | Every system has containment trees | Privileged container; tree structure |
| **Affordance** | Every system has named units of capability/behavior | Named declaration on a Module |
| **State** | Every stateful system has persistent data slots | Named data slot on a Module |
| **Type** | Every system has data shapes | Data shape with fields and combinators |
| **Relation** | Every system has typed connections between things | Typed edge (extensible) |
| **Tag** | Every system benefits from extensible classification | Label (extensible) |

**Boundary disappears as a primitive.** The bundle it carried (callable signature + exposure + behavior binding) decomposes:
- Callable signature → Affordance's `shape` attribute
- Exposure → Affordance's `role` attribute (project-defined values)
- Behavior binding → a separate Relation (e.g. `[?affordance :triggers ?rule]`)

A Module's "boundary" becomes a *derived view*, not a primitive: the set of its Affordances whose role indicates external participation. Different architectures define "external" differently; the substrate doesn't take a position, it just makes the query expressible.

### Affordance: the heavy lifter

An Affordance has:
- A **name** (identity within Module)
- The **Module** it belongs to (required)
- An optional **shape** — function-typed when present
- An optional **role** — project-defined value indicating external participation (or absent for internal/behavioural)
- An optional **formal expression** — rule body, invariant content, datalog (for behavioural assertions)
- Relations and Tags freely attached

**Three categories fall out:**

| Affordance kind | Has shape? | Has formal expression? |
|---|---|---|
| Function, emitter, handler, stream | Yes (function-typed) | Optional |
| Lifecycle hook, pure-effect call | Yes (`() -> ()`) | Optional |
| Rule, invariant, behavioural assertion | No | Yes |

`() -> ()` is reserved for genuinely invokable-but-dataless Affordances (lifecycle hooks). Rules and invariants don't get arrow notation; they have no shape attribute at all — they aren't functions, they're assertions.

**Constrained-when-present:** if an Affordance has a shape, it must be function-typed (arrow). Substrate rejects non-arrow shapes on Affordances. Keeps semantics tight.

### State: a minimal data slot

A State has:
- A **name**
- A **shape** (Type expression; required, must be value-typed/non-arrow)
- The **Module** it belongs to

Access semantics (private, exposed-read, exposed-write) live in **Relations** from Affordances to the State (`:reads`, `:writes`, `:initializes`), not as attributes on State. This keeps the substrate fully bare on access vocabulary, consistent with the going-bare commitment.

State is structurally simpler than Affordance — no role, no formal-expression — but distinct enough that calling state "an Affordance" stretches the word. Programmers reason about state and behaviour as different things; the substrate mirrors that distinction.

### Relation: the typed connection

A Relation has:
- A **from** (Entity id; required)
- A **kind** (namespaced keyword, project-defined; required)
- A **to** (Entity id; required)
- **Tags** may apply (optional)

**Substrate-level rules:**
- Relations are directed (from → to).
- Triples are unique — duplicate `(A :kind B)` entries dedupe.
- Relations are *not* themselves Entities — they have no IDs at substrate level. Tags-on-Relations are queryable via specialized syntax (see Constraint language below); promoting Relations to fully reified Entities is deferred.

**Two kinds of references in the substrate, distinguished:**

1. **Structural references** — built into a primitive's shape. A Module has child Modules/Affordances/States; an Affordance has a shape (Type) and a Module; a State has a shape (Type) and a Module; a record Type has fields (each name + Type). These are part of how primitives are *constructed* and traversed via dedicated APIs (`children-of`, `shape-of`, `fields-of`, etc.).
2. **Relations** — typed edges declared *separately* from primitives. `[?a :calls ?b]`, `[?aff :reads ?state]`, `[?command :mutates ?type]`. Open-ended kind vocabulary, project- or library-defined. Queried via Datalog patterns.

The substrate carves out structural references for things definitionally part of a primitive's identity. Everything architecturally-specific is a Relation. This tightens "going bare": substrate ships zero Relation kinds (libraries define their own), but the *line* between structural and relational is set by the substrate.

### What the substrate provides — and deliberately doesn't

**Zero shipped architectural vocabulary:**

| Slot | Substrate provides | Substrate ships | Filled by |
|---|---|---|---|
| Affordance roles | The slot | Nothing | Lift libraries (`fukan.canvas.cqrs`, `fukan.canvas.monolith`, project-defined) |
| Relation kinds | The slot | Nothing | Lift libraries / projects |
| Tag classes | The slot + TagDefinition mechanism | Nothing | Projects |
| State access modes | (Not a slot — handled by Relations) | — | Lift libraries via Relation vocabulary |

**Cost of going bare:** constraints written against one library's vocabulary won't run against another's. Cross-architecture portability is the casualty. Right cost to pay for architecture-neutrality.

**Substrate does ship Type combinators** (the one principled exception, because they're math-of-data, not architecture):

- **Atomic types** — `String`, `Int`, `Bool`, `Unit`, plus project-defined Types referenced by name
- **Record** — named map of typed fields
- **List** / **Set** — collection of one type
- **Optional** — value-or-nothing
- **Sum** — one-of-several
- **Function** / **Arrow** — the `A -> B` combinator

Type system *depth* (generics, refinements, recursive types) is deferred until real cases push for it.

### Isolation principle: declarations are self-contained

**A Module is fully specifiable from its own substrate + shared infrastructure.** Two rules:

1. **An Affordance's role declaration never names its consumers.** A reactive Affordance says `(role :reacts-to OrderPlaced)`; it doesn't say "reacts when Module Foo emits OrderPlaced." A function Affordance says `(role :exposed-call)`; it doesn't list callers.

2. **Cross-Module knowledge lives in Relations, not declarations.** Consumers reference producers via Relations (`[?caller :calls ?callee]`); these are *owned by the consuming side*. The used never references its users.

**Consequences:**
- Adding a new consumer never modifies an existing producer's declarations.
- Topics and other shared infrastructure live as Affordances in dedicated infrastructure Modules; references point into those registries.
- A Module can be analysed or designed independently of its consumers.

### Stress test summary

Five architectures were walked through during the design conversation: monolith Clojure (fukan itself), event-driven microservice, distributed CQRS, Erlang/actor system, static library/SDK. All express cleanly in the six-primitive substrate without straining. Edge cases (state semantics, protocols/interfaces, cross-cutting concerns, methods on Types, external systems) are expressible without new primitives — they want ergonomic lifts on top, not substrate revision.

## Canvas refinements — ergonomic consequences of the vision shift

These three refinements update iteration-one canvas decisions to align with the canvas-first / architecturally-neutral-substrate vision.

### Naming and abstract/realized

**Base form is `capability`, with `accepts` / `produces`.**

- `operation` → `capability` — "what the module can do," not "thing being done." Realization-neutral.
- `params` → `accepts`, `returns` → `produces` — data-flow vocabulary, not function-call vocabulary.
- Both `accepts` and `produces` optional. A capability with only `accepts` is sink-shaped; with only `produces`, source-shaped; with both, request-response-shaped.
- `triggers` stays — it's about causality (invoking this capability fires that rule), not transport.

**Decision 19 (abstract/realized split) stands and is sharpened.** The canvas commits to abstract structure only. Realization is deferred to its own future conversation. The vocabulary choices above are deliberately neutral so the realization layer can attach later without rewriting the abstract design.

**At substrate level, the most-neutral pair is `inputs` / `outputs`.** Lifts rename for architectural ergonomics:
- Monolith: `takes` / `gives`
- CQRS command: `intent` (no `outputs`)
- CQRS query: `params` / `returns`
- Event handler: `payload` on the inputs side
- Actor: `receives` / `sends`

Substrate stays generic; libraries impose architecturally-natural naming.

### Lift mechanism: composition, not inheritance

A lift produces substrate via helpers. The relationship between a lift like `command` and `capability` lives in the *substrate they both produce*, not in a lift type hierarchy. Composition keeps the mechanism flat: no `extends`, no abstract vs concrete lifts, no multi-base lifts, no override semantics.

**`defconstructor` exposes three or four things:**

1. **`(form …)` declarations** — each form has a name, docstring, a *shape* (closed shape vocabulary), and required-or-not. Universal forms (`description`, `scope`, `note`, `includes`, `excludes`) are implicit; lifts declare only their additions. A form may be marked `:repeatable` to appear multiple times in one instance.
2. **Shape vocabulary** — `field+`, `value-ref`, `type-ref`, `name-ref`, `prose`, `datalog`. Closed set.
3. **`(produces …)` block** — imperative Clojure receiving `name`, `doc`, and parsed body forms. Author calls substrate helpers to build instances. Imperative for iteration one; watch how lifts use the power, tighten if no one uses the flexibility.
4. **(Optional) `(composes-as …)`** — Datalog over produced substrate for post-translation assertions. Most lifts won't need it.

**Quality bar for errors:** malformed instances must generate diagnostic errors with available-form guidance. An LLM writing `(command Foo (returns …))` must see: *"`returns` is not a body form of `command`. Available forms: intent, mutates, description, scope, note. Did you mean `query`?"* This is part of the design, not an afterthought.

### Lift vs constraint: definitional in lift, policy in constraint

A lift's `composes-as` and a system constraint sit on the same constructive↔restrictive spectrum but serve different roles.

**Heuristic:**
- **Definitional** to the lift, exception-free, no granularity ever needed below it → fold into the lift.
- **Policy**, modifiable, has exceptions, sometimes needs to be relaxed → keep as a system constraint over separate lifts.

By this rule:
- "Every command has an intent" → lift (definitional).
- "Every command in this subsystem pairs with an event" → system constraint (policy).

**Critical principle: methodology libraries stay policy-free.** A `fukan.canvas.cqrs` library exports `command`, `query`, `event` with only definitional rules. A specific project that always broadcasts can either build its own `broadcasting-command` lift on top, or add a system constraint pairing events. The project decides where on the spectrum to sit; the methodology library leaves the choice open.

## Constraint language — query layer over the substrate

The substrate's queryability is exposed through Datalog. Lift composition rules, system constraints, and architect queries share the same machinery.

### Datalog as the constraint language

**Commit:** Datalog stays. Where it bites for LLMs (complex multi-join queries, negation, scoping) is mitigated by aggressive name resolution, `defquery` for frequently-used patterns, good error messages, and examples shipped with each lift library. The alternative (custom DSL + compiler) is real complexity without evidence yet that LLM-authoring is hopeless.

### Substrate as datoms

The substrate projects into a flat datom layer that Datalog queries:

```
;; Primitive identity
[?m :type :Module]    [?a :type :Affordance]
[?s :type :State]     [?t :type :Type]

;; Attributes
[?m :name "accounts"]
[?aff :shape ?shape] [?aff :role :command] [?aff :module ?m]

;; Structural references (flat projection of primitive structure)
[?m :child ?x]                                       ; Module's children
[?t :field ?f] [?f :field/name "x"] [?f :field/type ?type]
[?aff :shape-inputs ?type] [?aff :shape-output ?type]  ; arrow projection

;; Relations (project-defined kinds, flattened from triples)
[?a <kind> ?b]

;; Tags
[?e :tag :Deprecated]
```

Substrate stores rich structures; the datom layer is a query projection over them.

### Name resolution

Inside a constraint body, named things resolve to datom patterns — architects write the conceptually-obvious thing, expansion happens behind the scenes:

| Form in constraint | Expands to |
|---|---|
| `(Module ?x)` | `[?x :type :Module]` |
| `(Affordance ?x)` | `[?x :type :Affordance]` |
| `(SomeProjectType ?x)` | `[?x :type :Type] [?x :name "SomeProjectType"]` |
| Named entity `accounts` | `?accounts-id` (the entity id) |
| `this` | enclosing instance's entity id |
| `(tag :X ?e)` | `[?e :tag :X]` |
| Library-shipped relations | preloaded `defquery` expansion |
| Project-defined operators via `defquery` | term-rewriting expansion |

Library-shipped and project-defined operators use the *same mechanism*: `defquery`. The "library starter set" is just preloaded `defquery`s shipped by the library.

### `defquery` extension mechanism

```clojure
(defquery in-subsystem [?m ?s]
  "True when Module ?m is contained in subsystem ?s, recursively."
  '[(Module ?m) (Module ?s)
    (or [?s :child ?m]
        (and [?s :child ?intermediate]
             (in-subsystem ?m ?intermediate)))])
```

Pure Datalog bodies. Bodies can use other `defquery` operators (term-rewriting expansion). Computational predicates (`count`, `set-of`, `exists?`, `missing?`, standard aggregates) are shipped as built-ins, but arbitrary Clojure escape hatches are not allowed. Keeps the constraint language declarative and optimizable.

### `this` binding

Contextually bound:
- Inside a lift's `(composes-as …)`: `this` is the instance being checked.
- Inside an inline constraint on a Module: `this` is the enclosing Module.
- Top-level constraint: `this` is unbound. Using it is a parse error.

### Tags on Relations

Relations are pure triples (no IDs at substrate level). Tags applied to Relations are queryable via a specialized form:

```clojure
(relation-tag :Deprecated ?from :calls ?to)
```

Standard `[?from :calls ?to]` patterns work for from/kind/to. Tagged-relation queries use this dedicated form. If real cases push, the substrate can later promote Relations to fully-reified Entities (giving them IDs); for iteration one this is deferred.

### Namespacing

Relation kinds are namespaced keywords (`:fukan.canvas.cqrs/mutates`). Constraint authors use aliases established by the surrounding namespace's `:require :as` declarations. Inside a lift's `composes-as`, the lift's namespace aliases apply; inside a Module's inline constraint, the canvas namespace's aliases apply. Standard Clojure namespacing — no new machinery.

### Constraint identity

Constraints have names and locations but are *not* substrate entities. They're metadata on the canvas, not queryable as `(Constraint ?c)`. `fc/check` returns structured violation records.

### Violation output

```
{:constraint internal-to-clojure-target
 :message "source's operations are callable only from sibling Clojure target modules"
 :offenders [{:caller fukan.web.views.graph
              :op fukan.target.clojure.source/extract_symbols
              :reason "caller not in Clojure subsystem"}]
 :location {:file "fukan/target/clojure/canvas.clj" :form 23}}
```

Each violation: which constraint failed, the message, concrete offenders with variable bindings, source location.

### `fc/check` granularity

Run all composition + system constraints over the whole canvas. No incremental. Performance optimization is evidence-driven downstream work.

## Worked example: command lift on the new substrate

**Lift definition:**

```clojure
(defconstructor command
  "A state-changing capability that carries an intent payload
   and mutates a Value type."

  (form intent
    "The data the command carries."
    :shape (field+)
    :required)

  (form mutates
    "The Value type this command changes."
    :shape value-ref
    :required)

  (produces [name doc forms]
    (let [aff (affordance name doc
                :module *enclosing*
                :shape (arrow (record-of (:intent forms)) :unit)
                :role :command)]    ; project-defined role
      (apply-tag aff :fukan/command)
      (edge aff :mutates (resolve (:mutates forms))))))
```

(`affordance`, `arrow`, `record-of`, `apply-tag`, `edge`, `resolve` are speculative substrate-construction helper names exposed by `fukan.canvas`; the helper API isn't fully designed yet.)

**Architect usage:**

```clojure
(command CreateAccount
  "Register a new user account."
  (intent [email :String] [password :String])
  (mutates UserAccount))
```

The substrate produced: one Affordance with arrow shape `Record{email:String, password:String} -> Unit`, role `:command`, tagged `:fukan/command`, and a `:mutates` Relation to the `UserAccount` Type.

## Open threads

1. **Vision documents need updating.** `doc/VISION.md`, `doc/MODEL.md`, and `doc/DESIGN.md` were written under the iteration-one framing. Once this design is implemented, they need substantial revision to reflect the canvas-first / architecturally-neutral-substrate vision. Not blocking; flag for follow-up.
2. **Type system depth.** Generics, refinements, recursive types. Defer until real cases push for it.
3. **Mode B validation.** Does `defconstructor` earn its keep? Validate by writing methodological lift libraries and seeing what bites. The implementation plan (forthcoming) treats this as a primary verification activity.
4. **Runtime realization.** How the abstract design eventually maps to in-process calls, REST, gRPC, message queues, etc. Out of scope; deferred to its own conversation.
5. **Session history.** Branching, retraction, comparison of design variants. Deferred.

**Scope note for implementation:** If this design is committed to, it supersedes `.allium` and `.boundary` completely — all existing fukan specs must port to canvas. The layered-language setup (substrate / library / project) is the load-bearing claim that must be validated before extensive porting; verifying it ergonomically — by building a real library and using it against real specs — is the primary near-term goal.

## References

- `doc/plans/2026-05-25-architect-canvas.md` — iteration-one canvas design. This doc supersedes it in the parts that conflict (substrate framing, naming, lift mechanism shape) and extends it elsewhere. Most iteration-one decisions stand; the relationship between canvas and substrate is the load-bearing thing that shifted.
- `doc/VISION.md` — project vision. Needs updating to reflect the canvas-first framing.
- `doc/MODEL.md` — current substrate authoritative reference. Will need substantial revision once this redesign is implemented.
- `doc/DESIGN.md` — design principles. Needs updating to reflect canvas-as-primary-surface, substrate-as-minimal-kernel, lift-libraries-carry-architecture.
- `src/fukan/model/spec.allium` — current substrate spec. Legacy under this redesign.
