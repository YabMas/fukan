# Phase 7 Sprint 1 — Project-lens design (Layer A)

**Date:** 2026-05-27
**Status:** Draft for user review (pause point before Sprint 2 dispatch)
**Companion doc:** doc/plans/2026-05-27-scenario-handoff-design.md (Sprint 1 Task 2 — Layer B)

---

## Strategic frame

Phase 6 closed the canvas↔code feedback loop by tagging every projection edge `:valid | :absent` and emitting per-finding stable-ids + canonical addresses. Phase 7 turns those findings into actionable handoffs for an implementing LLM. The amendment at the bottom of the Phase 7 plan settles the load-bearing distinction this design honors: **Layer A produces a deterministic low-level code specification by projecting a generic Model element through a project-configured language lens; Layer B wraps that spec with scenario context.** The Model substrate carries no language assumptions — Affordance, Type, Module are language-neutral. Language gets bolted on in Layer A, via a per-project registry of `(Model-element-kind, lens-id) → projection-fn`.

The Clojure lens is the reference implementation. fukan-on-fukan registers it; an external project (a TypeScript codebase using fukan to govern its own design) ships its own lens analogue without forking fukan. The pluggability seed already exists — `src/fukan/project_layer/defaults.clj`'s `fukan-on-fukan` registry has `:root-prefix "fukan"` for the *addressing* side; Layer A extends that registry with the *structural-content* side.

A second framing the trial run surfaces: **canonical addressing is half-done.** `target/clojure/address.clj` already maps Model entity → file path + symbol; `target/clojure/projector.clj` already builds Blueprints in the analyzer direction (spec primitive → verified address + signature + context + idiom set + EDN/markdown rendering). What's new for Phase 7 is the **forward-direction structural template** — given a Model element + lens config, render the unambiguous Clojure (or whatever target form) the implementing LLM consumes. Layer A reuses `address.clj`'s canonical mapping; the *structural-content rendering* per Model-element kind is the new work.

---

## What the existing machinery already does

Layer A's projections reuse three existing namespaces. Documenting the reuse path here, so the projections don't re-implement work that already lives in `target/clojure/`.

### `target/clojure/address.clj` — canonical address derivation

The complete address-mapping fn:

```clojure
(addr/canonical registry primitive-kind projection-kind module-coord primitive-label)
;; → {:ns "fukan.infra.server" :name "start-server"}
```

Already handles:

- **Module-ns derivation.** `{root-prefix}.{module-coord-with-/-as-.}` with per-segment underscore→hyphen kebab-casing. `infra.server` → `fukan.infra.server`. `project_layer.registry` → `fukan.project-layer.registry`.
- **Local-name derivation.** PascalCase preserved for `:projection-kind/schema` (record-shaped types stay PascalCase as defs). PascalCase + snake_case both kebab-cased for function-shaped projection-kinds (`:rule`, `:operation`, `:invariant`, `:test`). E.g. `start_server` → `start-server`, `ProcessSubmission` → `process-submission`.
- **Test-projection suffixing.** `:projection-kind/test` appends `-test` to both ns and name.

**Reuse path:** every Layer A projection calls `addr/canonical` to produce its `:target.{namespace, symbol}` pair. Layer A then derives `:target.path` from the namespace via dot→slash + hyphen→underscore (the existing convention in `inspect/drift.clj`'s `ns->file-path`).

### `target/clojure/projector.clj` — analyzer-direction blueprint

This is the **analyzer-direction** projector. It already builds:

- Canonical address (via `addr/canonical`)
- Artifact kind (`:code/function` vs `:code/data-structure`) per projection-kind
- Expected signature (arglist, Malli pairs, return-malli, malli-shape for `[:map …]` containers)
- Surrounding model context (description + intent + related edges + host)
- Idioms (project-registry-routed templates by primitive-kind/projection-kind/address-pattern)
- EDN + markdown rendering

**Reuse decision for Phase 7.** Layer A's projections SHOULD NOT replicate the analyzer-direction blueprint mechanic verbatim. Instead, the Clojure lens calls *parts* of it:

| Existing fn | Layer A reuse |
|---|---|
| `addr/canonical` | Direct call. Every projection. |
| `projector/signature-for` (private) | Lift to public; reuse for `function-to-defn`, `type-to-malli`, `event-to-schema`. |
| `projector/select-idioms` (private) | Lift to public; reuse from any projection that wants registry-routed body templates. |
| `bp/to-markdown` | NOT reused. Layer A produces a different output shape (the project-lens map; Layer B renders the prompt). The blueprint's markdown is analyzer-side documentation; Layer A's prose lives in the projection map under `:prose`. |

**Sprint 1 surface item:** lift `signature-for` and `select-idioms` from `defn-` to `defn`. Document at the existing namespace, not by moving code. Sprint 2 Task 4 territory.

### `target/clojure/source.clj` — analyzer-side symbol extraction

Reverse direction: walks `.clj` files, extracts `def` / `defn` / `defn-` / `defrecord` as Code.* artifacts; for `def`s carrying `[:map …]` or `defrecord` field-vectors, also extracts a `:fields` slot.

**Not reused by Layer A.** Layer A goes the other way — Model element → rendered code. `source.clj` reads code; Layer A writes (templates) code. The shape that connects them is the Malli `[:map …]` literal: `source.clj` *parses* it into `[[fname ftype] …]` pairs; Layer A's `type-to-malli` *renders* it from `:type/fields` tuples. The aliasing table reconciles the two directions (Section "Alias normalization" below).

### `src/fukan/canvas/inspect/drift.clj` — the consumer

Drift findings carry `:offenders [{:stable-id ... :expected-code-path ... :expected-symbol ... :canvas-kind ...}]`. Layer A's `(project model-element)` will most often be invoked *from* a drift finding (Layer B's drift-close scenario). Layer A's input contract takes `model-element-id` (the stable-id from the offender); its first step is to look up the primitive in the model and dispatch.

---

## The lens contract

### Per-projection return shape (confirmed)

Every Layer A projection returns the same map shape, regardless of lens or Model-element kind:

```clojure
{:projection-kind     :clojure/type-to-malli          ; required; namespaced :<lens-id>/<projection-name>
 :lens-id             :clojure                        ; required; which lens produced this
 :model-element-kind  :Type                           ; required; the canvas substrate kind
 :model-element-id    "infra.server/type/ServerOpts"  ; required; canvas stable-id
 :target              {:path      "src/fukan/infra/server.clj"
                       :namespace "fukan.infra.server"
                       :symbol    "ServerOpts"}
 :template            "(def ServerOpts\n  [:map\n   [:port {:optional true} :int]])"
 :prose               "HTTP server configuration."
 :context             {:related-elements  ["infra.server/function/start_server"
                                           "infra.server/type/ServerInfo"]
                       :canvas-source-ref "canvas/infra/server.clj"
                       :doc-source        :canvas/record-doc}}
```

**Field semantics:**

- `:projection-kind` — namespaced keyword `<lens-id>/<projection-name>`. Lets a consumer disambiguate when the same Model-element kind has multiple projections (e.g. a Type might project via `:clojure/type-to-malli` OR `:clojure/type-to-defrecord` if that branch existed).
- `:lens-id` — the registered lens that produced this. `:clojure` for fukan-on-fukan.
- `:model-element-kind` — the canvas substrate kind, language-agnostic. `:Type`, `:Affordance` (role-disambiguated downstream), `:Module`. Reflects substrate vocabulary, not Clojure vocabulary.
- `:model-element-id` — the canvas stable-id. Round-trips with `inspect/drift.clj`'s `:offenders.stable-id`.
- `:target` — the address. Path + namespace + symbol. Derived from `addr/canonical` plus the path-derivation convention.
- `:template` — the rendered code, as a string. May be `nil` when structure is purely prose (an invariant's body is prose; the def shape is a `defn` stub but the property logic isn't templatable). The convention: where structure is determinate, render it; where it isn't, emit a structurally-safe stub and let `:prose` carry the intent.
- `:prose` — semantic intent. Pulled from the canvas declaration's docstring + `holds-that` clause + structural surroundings. Self-contained — the implementing LLM should not need to re-query canvas to get the intent.
- `:context` — non-load-bearing additional information. `:related-elements` is a vector of stable-ids of other entities the implementing LLM might want to know about (same module siblings, referenced types). `:canvas-source-ref` points at the source file. `:doc-source` records which canvas attribute the prose came from (`:canvas/record-doc`, `:canvas/invariant-holds-that`, etc.) for ergonomics.

### Registry mechanism (confirmed multimethod with caveat)

The user default is **multimethod keyed on `[lens-id Model-element-kind]`**. Confirmed with one refinement.

```clojure
;; src/fukan/canvas/project/core.clj
(defmulti project
  "Project a Model element through a registered lens. Dispatches on
   [lens-id, dispatch-key] where dispatch-key is the canvas-role for Affordances
   and :Type/:Module otherwise."
  (fn [lens-id model-element _opts]
    [lens-id (dispatch-key-of model-element)]))

(defn dispatch-key-of
  "For Affordances, dispatch on :canvas-role (because invariant/rule/function/
   getter/checker/event/handler all have :model-element-kind :Affordance but
   different projection contracts). For Types and Modules, dispatch on the kind."
  [{:keys [model-element-kind canvas-role]}]
  (case model-element-kind
    :Affordance canvas-role             ; :canvas/operation, :canvas/invariant, etc.
    model-element-kind))                ; :Type, :Module
```

**Refinement (why two-level dispatch):** Affordances cover 7 canvas roles (function, getter, checker, invariant, rule, event, handler). All ship as `:model-element-kind :Affordance`. A single `(project :clojure {:model-element-kind :Affordance ...})` cannot decide between `function-to-defn` and `invariant-to-predicate`. The cheapest disambiguation is dispatching on `[lens-id, canvas-role-or-element-kind]` — three of the dispatch keys point at the Type+Module entry, the other seven point at the canvas-role for Affordances.

This keeps the surface clean: one registration per (lens, role) pair, no nested case-switching inside the projection fns.

**Registration shape (per projection):**

```clojure
;; src/fukan/canvas/project/clojure/function_to_defn.clj
(ns fukan.canvas.project.clojure.function-to-defn
  (:require [fukan.canvas.project.core :refer [project]]
            [fukan.target.clojure.address :as addr]))

(defmethod project [:clojure :canvas/operation]
  [_lens-id element opts]
  (let [...]
    {:projection-kind     :clojure/function-to-defn
     :lens-id             :clojure
     :model-element-kind  :Affordance
     :model-element-id    (:stable-id element)
     :target              (target-for element opts)
     :template            (render-defn element)
     :prose               (:doc element)
     :context             {...}}))
```

The registry namespace (`src/fukan/canvas/project/registry.clj`) does little besides ensure each per-projection namespace is `require`d at boot. Clj-reload runs each `(defmethod project …)` at load time; the multimethod's registry-of-implementations doubles as the lens registry.

**Discovery:** `src/fukan/canvas/project/clojure/registry.clj` `require`s every Clojure-lens projection namespace explicitly. A future external project (e.g. `fukan-typescript`) would add a `requires-typescript-lens.clj` doing the same for its projections. This mirrors the `canvas-source/canvas-builders` pattern from Phase 6's auto-discover decision (which auto-scans canvas files; the projection registry stays explicit since each `defmethod` is a load-bearing edge).

### Pluggability sketch (TypeScript lens)

A hypothetical external project using fukan for a TypeScript codebase would ship:

```clojure
;; src/fukan/canvas/project/typescript/registry.clj
(ns fukan.canvas.project.typescript.registry
  (:require [fukan.canvas.project.typescript.type-to-interface]
            [fukan.canvas.project.typescript.function-to-function-decl]
            [fukan.canvas.project.typescript.event-to-type-alias]
            ...))
```

```clojure
;; src/fukan/canvas/project/typescript/type_to_interface.clj
(ns fukan.canvas.project.typescript.type-to-interface
  (:require [fukan.canvas.project.core :refer [project]]))

(defmethod project [:typescript :Type]
  [_lens-id element opts]
  {:projection-kind     :typescript/type-to-interface
   :lens-id             :typescript
   :model-element-kind  :Type
   :model-element-id    (:stable-id element)
   :target              {:path "src/types/" (camel-name element) ".ts"
                         :namespace nil      ; TypeScript has modules-by-file
                         :symbol (pascal-name element)}
   :template            (render-interface element)
   :prose               (:doc element)
   :context             {...}})
```

The lens contract makes no Clojure-shaped assumptions. `:target.namespace` may be `nil`; `:template` is a string in any syntax; `:prose` is plain English; `:context` is open-shape. The substrate-side `dispatch-key-of` handles only the canvas vocabulary (Affordance roles + Type + Module), which IS language-agnostic.

**The lens registry itself lives in fukan**, but each project's lens code lives in the project. The shipped fukan distribution carries the Clojure lens because fukan-on-fukan is the reference case; external projects' lens namespaces live alongside their canvas, on their classpath.

---

## Clojure lens — per-projection catalog

Six projections ship in Phase 7. Listed in suggested implementation order (cheapest first). Each section shows the projection against a real fukan-on-fukan canvas declaration.

### 1. `value-to-def` — `:Type` (atomic kind) → opaque marker

**Routes:** `[:clojure :Type]` (atomic branch — see selection refinement below).

**What it produces.** A `def` declaring an opaque named type — typically a Malli alias for `:any` with a `{:description ...}` options map, since fukan's existing Clojure idiom for opaque types is the `^:schema` marker convention.

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"NodeId"`)
- `:entity/doc`
- `:type/kind` (`:atomic`)
- The canvas module's stable-id (for address derivation)

**Inputs (address-side):**

- `addr/canonical registry :primitive/container :projection-kind/schema module-coord "NodeId"` → `{:ns "fukan.distributed.cluster" :name "NodeId"}`

**Sample — `canvas/distributed/cluster.clj :: NodeId` → `src/fukan/distributed/cluster.clj`:**

```clojure
{:projection-kind     :clojure/value-to-def
 :lens-id             :clojure
 :model-element-kind  :Type
 :model-element-id    "distributed.cluster/type/NodeId"
 :target              {:path      "src/fukan/distributed/cluster.clj"
                       :namespace "fukan.distributed.cluster"
                       :symbol    "NodeId"}
 :template            "(def ^:schema NodeId\n  [:any {:description \"An opaque, stable identity for a cluster member. Distinct from the transport-layer address; survives restarts and rebinds.\"}])"
 :prose               "An opaque, stable identity for a cluster member. Distinct from the transport-layer address; survives restarts and rebinds."
 :context             {:related-elements  ["distributed.cluster/type/Node"
                                           "distributed.cluster/type/Cluster"
                                           "distributed.cluster/function/get_node"]
                       :canvas-source-ref "canvas/distributed/cluster.clj"
                       :doc-source        :canvas/value-doc}}
```

**Structural template (literal where structure is determinate):**

```
(def ^:schema {{symbol}}
  [:any {:description "{{escaped-prose}}"}])
```

**Edge cases:**

- *Missing doc.* Canvas authors are encouraged to write docstrings; if absent, omit the `:description` key entirely (`[:any]`).
- *Future opacity refinement.* If the canvas adds a `(constraint ...)` form to a value declaration in some later phase, the template would extend to carry the constraint. Phase 7 doesn't depend on that.

**Why first.** Smallest projection. Zero dependence on signature rendering. Pure name + doc → templated def. Tests are mechanical.

### 2. `type-to-malli` — `:Type` (record kind) → Malli `[:map …]` schema

**Routes:** `[:clojure :Type]` (record branch).

**Dispatch refinement.** Both atomic and record types ship as `:model-element-kind :Type`. Two ways to disambiguate:

- **(A) Sub-dispatch inside one `defmethod`.** `[:clojure :Type]` examines `:type/kind` and branches to `value-to-def` vs `type-to-malli` internally.
- **(B) Per-kind dispatch.** Add `type/kind` to the dispatch key: `[:clojure :Type :record]`, `[:clojure :Type :atomic]`.

**Recommendation: (A).** The multimethod stays small (one dispatch per Model-element-kind); the branch inside is explicit; it matches the registry's intuition that "Type is one projection target with shape variants." Sprint 1 leaves this open for Sprint 3 implementation; either is fine but (A) is cheaper.

**What it produces.** A `def` carrying a Malli `[:map …]` schema. Field pairs are derived from canvas `:type/fields` tuples, normalized through the alias table.

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"ServerOpts"`)
- `:entity/doc`
- `:type/fields` (set of `[fname fshape]` tuples)
- The canvas module's stable-id

**Inputs (alias-side):**

- `canvas->malli-aliases` table (already in `inspect/drift.clj`) for scalar normalization.
- Compound-shape unwrapping for `(optional :T)`, `(list-of :T)`, `(set-of :T)`, `(map-of :K :V)`, `(sum-of …)`, `(ref-to :ns/Name)`. Compound rendering rules live in this projection.

**Sample — `canvas/infra/server.clj :: ServerOpts` → `src/fukan/infra/server.clj`:**

```clojure
{:projection-kind     :clojure/type-to-malli
 :lens-id             :clojure
 :model-element-kind  :Type
 :model-element-id    "infra.server/type/ServerOpts"
 :target              {:path      "src/fukan/infra/server.clj"
                       :namespace "fukan.infra.server"
                       :symbol    "ServerOpts"}
 :template            "(def ^:schema ServerOpts\n  [:map {:description \"HTTP server configuration.\"}\n   [:port {:optional true} :int]])"
 :prose               "HTTP server configuration."
 :context             {:related-elements  ["infra.server/function/start_server"
                                           "infra.server/type/ServerInfo"]
                       :canvas-source-ref "canvas/infra/server.clj"
                       :doc-source        :canvas/record-doc}}
```

**Structural template:**

```
(def ^:schema {{symbol}}
  [:map {:description "{{escaped-prose}}"}
   {{#each fields}}
   [{{field-name-kw}} {{?optional}} {{type-rendered}}]
   {{/each}}])
```

**Compound shape rendering rules:**

| Canvas shape | Renders as |
|---|---|
| `:Integer` | `:int` |
| `:String` | `:string` |
| `(optional :T)` | `[:maybe {{rendered-T}}]` — drops outer `optional`; renders inner |
| `(list-of :T)` | `[:sequential {{rendered-T}}]` |
| `(set-of :T)` | `[:set {{rendered-T}}]` |
| `(map-of :K :V)` | `[:map-of {{K}} {{V}}]` |
| `(sum-of :A :B …)` | `[:or {{A}} {{B}} …]` |
| `(ref-to :ns/Name)` | `:ns/Name` (reference symbol; resolved at consumption by Malli registry; alternatively render as a qualified keyword the consuming code has registered) |
| `:ModuleType` (local ref) | `:ModuleType` (keyword preserved; assumed registered by sibling def) |

**Optionality:** when the outer shape is `(optional :T)`, the field-entry gets `{:optional true}` instead of nesting `[:maybe …]`. Two equivalent options exist; the field-level convention reads more idiomatically and matches existing fukan style (`ServerOpts`'s port field).

**Edge cases:**

- *Missing canvas doc.* Drop the `:description`.
- *Unknown scalar.* If canvas declares `(field x :SomeUndocumentedType)` and the alias table has no entry, render the keyword verbatim and let the implementing LLM (or `clojure-lsp`) flag the unresolved symbol. The projection doesn't validate; that's drift's job.
- *Sum-of with refs to other canvas types.* The rendered `:or` enumerates each variant. If a variant doesn't have a Layer A projection yet (a fresh canvas declaration before its sibling lands), the rendered symbol still appears — the implementing LLM sees the gap and can wait or signal back.
- *Self-referential record* (rare; field type references the enclosing type). Render the keyword; the Malli registry handles the cycle.

### 3. `event-to-schema` — `:Affordance` (role `:canvas/event`) → schema def

**Routes:** `[:clojure :canvas/event]`.

**What it produces.** A `def` carrying a Malli `[:map …]` schema for the event payload. Mirrors `type-to-malli` shape but carries event-specific naming and an `:event` tag.

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"LeaderElected"`)
- `:entity/doc`
- `:event/payload` shape (parsed by canvas event vocab into a `:record` with field pairs)
- Module stable-id

**Sample — hypothetical `canvas/distributed/election.clj :: LeaderElected`:**

```clojure
{:projection-kind     :clojure/event-to-schema
 :lens-id             :clojure
 :model-element-kind  :Affordance
 :model-element-id    "distributed.election/event/LeaderElected"
 :target              {:path      "src/fukan/distributed/election.clj"
                       :namespace "fukan.distributed.election"
                       :symbol    "LeaderElected"}
 :template            "(def ^:event LeaderElected\n  [:map {:description \"Emitted when a new leader for a term is elected.\"}\n   [:leader :NodeId]\n   [:term :Term]])"
 :prose               "Emitted when a new leader for a term is elected."
 :context             {:related-elements  ["distributed.cluster/type/NodeId"
                                           "distributed.cluster/type/Term"]
                       :canvas-source-ref "canvas/distributed/election.clj"
                       :doc-source        :canvas/event-doc}}
```

**Structural template:**

```
(def ^:event {{symbol}}
  [:map {:description "{{escaped-prose}}"}
   {{#each payload-fields}}
   [{{field-name-kw}} {{type-rendered}}]
   {{/each}}])
```

**Edge cases:**

- *Empty payload.* `(event "Tick" "Time advanced.")` → renders `[:map {:description "Time advanced."}]`. Valid empty Malli map. Implementing LLM may decide to omit the `def` entirely, or write a marker; the projection still produces it.
- *Payload referencing other canvas types.* Same as `type-to-malli` — render the keyword; absent siblings show up as drift in a later run.

### 4. `function-to-defn` — `:Affordance` (role `:canvas/operation`) → defn

**Routes:** `[:clojure :canvas/operation]`.

**What it produces.** A `defn` with arglist derived from `takes`, optional Malli schema metadata derived from `gives`, docstring from canvas, and an exception-stub body. The implementing LLM is expected to replace the body with real logic.

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"start_server"`)
- `:entity/doc`
- `:affordance/shape` (an `:arrow` with `:inputs` record + `:outputs` shape)
- `:affordance/role` (`:canvas/operation` here)
- `:affordance/effects` (if any — surfaced in prose only)
- `:affordance/emits` (if any — surfaced in prose + comment hint)
- Module stable-id

**Inputs (address-side):**

- `addr/canonical registry :primitive/operation :projection-kind/operation module-coord "start_server"` → `{:ns "fukan.infra.server" :name "start-server"}`
- `projector/signature-for` (lifted to public) for arglist + Malli rendering.

**Sample — `canvas/infra/server.clj :: start_server` → `src/fukan/infra/server.clj`:**

```clojure
{:projection-kind     :clojure/function-to-defn
 :lens-id             :clojure
 :model-element-kind  :Affordance
 :model-element-id    "infra.server/function/start_server"
 :target              {:path      "src/fukan/infra/server.clj"
                       :namespace "fukan.infra.server"
                       :symbol    "start-server"}
 :template            "(defn start-server\n  \"Start the HTTP server on the given options.\"\n  {:malli/schema [:=> [:cat :ServerOpts] [:maybe :ServerInfo]]}\n  [opts]\n  (throw (ex-info \"start-server: not yet implemented\"\n                  {:canvas-id \"infra.server/function/start_server\"})))"
 :prose               "Start the HTTP server on the given options."
 :context             {:related-elements  ["infra.server/type/ServerOpts"
                                           "infra.server/type/ServerInfo"
                                           "infra.server/function/stop_server"
                                           "infra.server/getter/get_port"]
                       :canvas-source-ref "canvas/infra/server.clj"
                       :doc-source        :canvas/function-doc
                       :effects           []
                       :emits             []}}
```

**Compare to actual `src/fukan/infra/server.clj`:**

```clojure
(defn start-server
  "Start HTTP server.

   Options:
     :port - Server port (default: 8080)"
  {:malli/schema [:=> [:cat :ServerOpts] [:maybe :ServerInfo]]}
  [{:keys [port] :or {port 8080}}]
  (if @state
    (println "Server already running on port" (:port @state))
    ...))
```

The projection produces the *skeleton* (signature + Malli schema + docstring + stub body); the implementing LLM fills in the body, may refine the destructuring, may extend the docstring with examples. The canvas-side declaration's `(takes [opts :ServerOpts])` translates to `[opts]` in the arglist (canvas commits to one binding name; the LLM may destructure as needed).

**Structural template:**

```
(defn {{kebab-name}}
  "{{escaped-doc}}"
  {:malli/schema [:=> [:cat {{param-types}}] {{return-malli}}]}
  [{{arglist}}]
  (throw (ex-info "{{kebab-name}}: not yet implemented"
                  {:canvas-id "{{stable-id}}"})))
```

**Edge cases:**

- *Zero-arg.* `(takes [])` → arglist `[]`, `:=> [:cat]` → no inner types.
- *Multiple parameters.* `(takes [a :Int] [b :String])` → arglist `[a b]`, `:=> [:cat :int :string]`.
- *`gives :Unit`.* Render return-malli as `:nil`. (Convention: canvas's `:Unit` maps to Malli `:nil` for return positions, consistent with fukan's own `stop-server` signature.)
- *Effects declared.* Carry the list of effects in `:context.effects`; surface in `:prose` as a sentence appended ("Performs effect: log-write."). The implementing LLM uses this as a hint.
- *Emits events.* Same treatment — carry in `:context.emits`; surface as prose. Comment hint inside the stub body is feasible but adds noise; recommend prose-only.

### 5. `invariant-to-predicate` — `:Affordance` (role `:canvas/invariant`) → predicate fn

**Routes:** `[:clojure :canvas/invariant]`.

**What it produces.** A `defn` predicate named after the `holds-that` clause (kebab-cased), taking `[model]` and returning a boolean. The body is a `throw`-stub; the prose carries the semantic intent (what the invariant means) so the implementing LLM can write the actual property check.

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"TermMonotonicity"`)
- `:entity/doc` (the descriptive prose)
- `:affordance/formal-expression` (the `holds-that` clause string — names the predicate)
- Module stable-id

**Inputs (address-side):**

- The predicate's local-name comes from kebab-casing the `holds-that` text. `addr/canonical` already handles this via the `:projection-kind/invariant` route — local-name derivation snake→kebab and pascal→kebab of the *primitive label* (which is the holds-that text for invariants per the Phase 6 carryover note).

**Sample — `canvas/distributed/cluster.clj :: TermMonotonicity` → `src/fukan/distributed/cluster.clj`:**

```clojure
{:projection-kind     :clojure/invariant-to-predicate
 :lens-id             :clojure
 :model-element-kind  :Affordance
 :model-element-id    "distributed.cluster/invariant/TermMonotonicity"
 :target              {:path      "src/fukan/distributed/cluster.clj"
                       :namespace "fukan.distributed.cluster"
                       :symbol    "current-term-is-monotonically-non-decreasing-per-node"}
 :template            "(defn current-term-is-monotonically-non-decreasing-per-node\n  \"The current_term observed by any node never decreases. A node that\n  learns of a higher term adopts it; a node that observes a lower\n  term ignores or rejects the message.\"\n  [model]\n  (throw (ex-info \"current-term-is-monotonically-non-decreasing-per-node: not yet implemented\"\n                  {:canvas-id \"distributed.cluster/invariant/TermMonotonicity\"\n                   :invariant-name \"TermMonotonicity\"\n                   :holds-that \"current_term is monotonically non-decreasing per node\"})))"
 :prose               "Invariant: TermMonotonicity. The current_term observed by any node never decreases. A node that learns of a higher term adopts it; a node that observes a lower term ignores or rejects the message.\n\nWhat must hold: current_term is monotonically non-decreasing per node.\n\nProperty-check approach: this predicate evaluates the model and returns true iff the invariant holds. The implementing LLM should walk the model (cluster nodes; their current_term values; an observation history if recorded) and verify the monotonicity property."
 :context             {:related-elements  ["distributed.cluster/type/Cluster"
                                           "distributed.cluster/type/Term"
                                           "distributed.cluster/invariant/AtMostOneLeaderPerTerm"
                                           "distributed.cluster/invariant/MajorityRequiredForLeadership"]
                       :canvas-source-ref "canvas/distributed/cluster.clj"
                       :doc-source        :canvas/invariant-doc+holds-that
                       :holds-that        "current_term is monotonically non-decreasing per node"
                       :invariant-name    "TermMonotonicity"}}
```

**Structural template:**

```
(defn {{predicate-name}}
  "{{escaped-doc}}"
  [model]
  (throw (ex-info "{{predicate-name}}: not yet implemented"
                  {:canvas-id "{{stable-id}}"
                   :invariant-name "{{invariant-name}}"
                   :holds-that "{{holds-that}}"})))
```

**Prose envelope** (this is where the projection's *intent transfer* lives):

```
Invariant: {{invariant-name}}. {{descriptive-doc}}

What must hold: {{holds-that}}.

Property-check approach: this predicate evaluates the model and returns true iff the invariant holds. The implementing LLM should walk the model (related entities) and verify the property.
```

**Edge cases:**

- *Phase 6 carryover (rule+invariant collision in primitives map).* Today, invariants and rules collapse onto `:primitive/rule` in the canvas-source projection, and the projection-kind reads `:projection-kind/invariant` only by route — drift's `:expected-symbol` carries the holds-that clause for invariants but the rule's own name for rules. Layer A's `invariant-to-predicate` reads from the primitive's `:canvas-role :canvas/invariant` *plus* the `:formal-expression`'s `holds-that` clause; the address derivation goes through `addr/canonical` with `:projection-kind/invariant` which already lowers the holds-that text to kebab. **No new code needed here**, but Sprint 2 Task 4 should confirm `:formal-expression` is reachable from the model element. If it's lost in projection, Layer A would need to read it from the canvas db directly (a parallel path canvas-source already opens for `inspect/drift.clj`'s shape-drift).
- *Empty `holds-that`.* If a canvas author writes `(invariant "X" "...doc only..." )` without a `holds-that` form, the predicate name would have to fall back to the kebab of the invariant name itself. Add this fallback to `addr/canonical`'s invariant branch if it's not already there.
- *Property logic complexity.* Invariants are inherently semantic; the projection's prose envelope is the *primary* output. The implementing LLM writes the property check. This is the canonical "structure leaves semantic intent" case the Phase 7 plan calls out.

### 6. `rule-to-predicate` — `:Affordance` (role `:canvas/rule`) → predicate fn

**Routes:** `[:clojure :canvas/rule]`.

**What it produces.** Symmetric with `invariant-to-predicate` but named off the rule's own name (kebab-cased), and the prose envelope frames it as a *reactive* declaration with a `when` trigger pattern (carried from `:formal-expression.when`).

**Inputs (canvas-side):**

- `:entity/name` (e.g. `"VoteGranted"`)
- `:entity/doc`
- `:affordance/formal-expression.when` (the trigger pattern, a vec)
- Module stable-id

**Sample — hypothetical `canvas/distributed/election.clj :: GrantVoteOnRequest`:**

```clojure
{:projection-kind     :clojure/rule-to-predicate
 :lens-id             :clojure
 :model-element-kind  :Affordance
 :model-element-id    "distributed.election/rule/GrantVoteOnRequest"
 :target              {:path      "src/fukan/distributed/election.clj"
                       :namespace "fukan.distributed.election"
                       :symbol    "grant-vote-on-request"}
 :template            "(defn grant-vote-on-request\n  \"Reactive rule: a node grants its vote to a candidate when the\n  candidate's term is at least as new and the node has not already\n  voted in this term.\"\n  [model]\n  (throw (ex-info \"grant-vote-on-request: not yet implemented\"\n                  {:canvas-id \"distributed.election/rule/GrantVoteOnRequest\"\n                   :rule-name \"GrantVoteOnRequest\"\n                   :when (quote (RequestVoteReceived candidate term))})))"
 :prose               "Reactive rule: GrantVoteOnRequest. A node grants its vote to a candidate when the candidate's term is at least as new and the node has not already voted in this term.\n\nTrigger: (RequestVoteReceived candidate term). The rule predicate evaluates the model after the trigger fires and returns true iff the rule's reactive condition holds."
 :context             {:related-elements  ["distributed.election/event/RequestVoteReceived"
                                           "distributed.cluster/invariant/AtMostOneLeaderPerTerm"]
                       :canvas-source-ref "canvas/distributed/election.clj"
                       :doc-source        :canvas/rule-doc+when
                       :when              "(RequestVoteReceived candidate term)"
                       :rule-name         "GrantVoteOnRequest"}}
```

**Structural template:**

```
(defn {{kebab-rule-name}}
  "{{escaped-doc}}"
  [model]
  (throw (ex-info "{{kebab-rule-name}}: not yet implemented"
                  {:canvas-id "{{stable-id}}"
                   :rule-name "{{rule-name}}"
                   :when (quote {{when-pattern}})})))
```

**Edge cases:**

- *`when`-pattern with parameter pairs.* The vec `[Trig (param :Type)]` renders as a quoted form for documentation purposes only — the implementing LLM uses it as a hint, not as executable code.
- *Rules vs functions confusion.* Canvas rules are reactive declarations; functions are direct-call entry points. The projection's `:projection-kind :clojure/rule-to-predicate` keeps the two distinguishable.

---

## Deferred Phase-7 projections (rationale)

- **`getter-to-defn`.** Special case of `function-to-defn` with arglist `[]` and return-shape `(optional :T)` → `:=> [:cat] [:maybe :T]`. Structurally identical to function-to-defn modulo signature. *Could* ship in Phase 7 trivially by routing `[:clojure :canvas/getter]` to a thin wrapper. Recommendation: defer to Phase 7.5 unless the trial-run in Sprint 4 wants it. Cheap; just scope discipline.
- **`handler-to-defn`.** Routes `[:clojure :canvas/handler]`. The `on` form names the incoming event; `emits` names outgoing events. Signature shape is `[event-payload]` → `:=> [:cat <PayloadType>] :nil`. Semantic overlap with `function-to-defn` but the emits-list IS load-bearing for prose. Defer to Phase 7.5.
- **`checker-to-defn`.** Routes `[:clojure :canvas/checker]`. Signature is baked in: `(check [model]) → [Violation]`. Very mechanical. Defer to Phase 7.5; the trial run will tell us if it's wanted.
- **`update-record-fields`** (shape-drift handling). Needs Sprint 2's compound-shape comparator AND a delta-rendering shape Layer A's "fresh-write" projection doesn't carry. Phase 8 territory.

---

## Alias normalization

The Phase 6 alias table in `src/fukan/canvas/inspect/drift.clj` is the canonical mapping. Layer A reuses it verbatim for scalars. Compound shapes need rendering rules (not aliases — they have structure, not just a name swap).

### Scalar alias table (canonical, lives in `inspect/drift.clj`)

```clojure
{:Integer  :int      :String   :string   :Boolean  :boolean
 :Float    :float    :Double   :double   :Long     :long
 :Keyword  :keyword  :Symbol   :symbol
 :Map      :map      :Vector   :vector   :Set      :set
 :Any      :any      :Unit     :any}
```

**Extensions surfaced by per-projection design.**

The current Phase 6 table is for *drift comparison* — it goes both ways (`:Integer ↔ :int`). For Layer A's *one-way rendering* (canvas → code), most entries apply unchanged. Two refinements:

1. **Return-position `:Unit`.** For function/getter return types, `:Unit` renders as `:nil` (idiomatic for "no meaningful value" in Malli return positions) rather than `:any`. The alias table is shape-position-blind; Layer A's `function-to-defn` post-processes return-shapes to swap `:any → :nil` when the canvas declaration was `:Unit`. Sprint 1 surface item: either extend the alias table with a `:return-position` sub-table, or keep the swap localized in `function-to-defn`. Recommendation: localized swap; the alias table stays general.

2. **`:NodeId`-style local references.** Currently the table has no entry for module-local PascalCase references like `:NodeId`, `:Term`, `:Cluster`. These pass through unchanged (drift-side: lower-cased? no — they stay PascalCase; the alias is `:NodeId → :NodeId`). Layer A renders them verbatim. The implementing LLM's Malli registry resolves them at runtime.

### Compound shape rendering (Layer A territory; documented here, implemented in `type-to-malli`)

| Canvas | Malli rendering |
|---|---|
| `(optional :T)` | `[:maybe <T>]` (or `{:optional true} <T>` in field-entry position) |
| `(list-of :T)` | `[:sequential <T>]` |
| `(set-of :T)` | `[:set <T>]` |
| `(map-of :K :V)` | `[:map-of <K> <V>]` |
| `(sum-of :A :B …)` | `[:or <A> <B> …]` |
| `(record-of [n :T] …)` | `[:map [<n> <T>] …]` (inline record) |
| `(ref-to :ns/Name)` | `:ns/Name` |

This table belongs in the Layer A core (`src/fukan/canvas/project/core.clj`) as a `compound-shape→malli` renderer fn that `type-to-malli` and `event-to-schema` both call. Naming: `render-shape` taking a canvas shape map and returning a Malli expression. Sprint 3 Task 5 implements.

---

## Reuse path through `target/clojure/address.clj` — summary

| Projection | Calls `addr/canonical` with | Notes |
|---|---|---|
| `value-to-def` | `:primitive/container :projection-kind/schema` | Atomic types still route through schema projection-kind (preserves PascalCase) |
| `type-to-malli` | `:primitive/container :projection-kind/schema` | Same |
| `event-to-schema` | `:primitive/event :projection-kind/schema` | Event payload also routes as schema; `addr/canonical` should accept `:primitive/event` — verify in Sprint 2 |
| `function-to-defn` | `:primitive/operation :projection-kind/operation` | Kebab-lower local-name; existing path |
| `invariant-to-predicate` | `:primitive/rule :projection-kind/invariant` + holds-that text as label | The Phase 6 carryover: invariant labels are holds-that text |
| `rule-to-predicate` | `:primitive/rule :projection-kind/rule` + rule-name as label | Existing path |

**Addressing gaps surfaced by Layer A's design:**

1. **Event schema address.** Today `addr/canonical`'s `data-structure-kinds` accepts only `:projection-kind/schema`, and the primitive-kind table doesn't distinguish event vs container for schema routing. `event-to-schema` needs the same address-derivation path as `type-to-malli` modulo no functional change. Verify in Sprint 2 Task 4 that an event primitive routed through `:projection-kind/schema` produces the right `{:ns ... :name "LeaderElected"}` output. If not, extend `addr/canonical` to accept `:primitive/event` symmetrically.

2. **Module-only addresses.** Layer A doesn't need `module-to-namespace` projections in Phase 7, but the existing `module-ns` fn already supports the lookup. If a future cold-write scenario asks for the module's bare ns (for the `(ns ...)` form at the top of a fresh src file), this is a public helper, no extension needed.

3. **Invariant fallback.** If a canvas `invariant` has no `holds-that` clause, `addr/canonical` would receive an empty label. Today this throws or produces a junk name. Sprint 2 Task 4 candidate: add a fallback to `kebab(invariant-name)`.

---

## Tier verdict (confirmed)

**Trust tier, `:severity :info`.**

The projection is mechanical: given a Model element + a lens config, the output is deterministic. There's no methodology judgment involved. The implementing LLM may decide not to act on a projection (the canvas-author may decide the canvas should move instead of the code), but the projection itself is a fact. `:info` reflects "informational; no error; here's the deterministic spec if you choose to write it."

No push-back. The "spec is fact" framing carries through cleanly from Phase 6's drift findings ("discrepancy is fact"). The trust tier holds.

---

## Stub-template ambition verdict (confirmed with one refinement)

**Bare signature + exception body + prose envelope. Confirmed.**

Reasoning:

- Richer templates risk being *wrong* in load-bearing ways. The implementing LLM is a capable coding agent; constraining it with elaborate templates moves the dispatch axis from "structure + intent" to "fill in this template." The Phase 7 plan's bet is the former.
- Exception bodies are structurally safe — the code compiles, the project loads, tests can run (and will fail on the throw, which is correct: an unimplemented function should fail loudly).
- The `ex-info` map carries `:canvas-id` (the stable-id) — this lets a future Phase 8 verification step automatically detect "still unimplemented" without parsing source.

**Refinement:** the exception body carries enough metadata to round-trip. The `ex-info` payload is `{:canvas-id "..." [:invariant-name|:rule-name "..."] [:holds-that|:when "..."]}` per projection. This is the structurally-safe minimum that also doubles as audit trail.

**Why not richer.** A `function-to-defn` projection *could* try to derive a body skeleton from the canvas-declared effects + emits (e.g. "this fn emits X, so the body should `(emit :X ...)` somewhere"). The trade-off: that's correct often but wrong sometimes, and the implementing LLM is more reliable at composing the body from the prose+effects hints than the template author was. Defer richer stubs to Phase 8+ if trial-run evidence shows the implementing LLM is missing the effect/emit hints.

---

## Phase 7 priority — confirmed projection set

**Ship in Phase 7 (6 projections):**

1. `value-to-def`
2. `type-to-malli`
3. `event-to-schema`
4. `function-to-defn`
5. `invariant-to-predicate`
6. `rule-to-predicate`

**Defer to Phase 7.5 or Phase 8:**

- `getter-to-defn` — trivial wrapper; ship if Sprint 4 trial pulls it
- `handler-to-defn` — straightforward; same
- `checker-to-defn` — trivial; same
- `update-record-fields` — needs Sprint 2 compound-shape comparator; Phase 8

**Why 6.** The default of 6 covers the cardinal Model-element shapes:

- 2 Type variants (atomic + record)
- 1 Event variant (schema)
- 1 Function variant (defn skeleton)
- 2 Predicate-shaped affordances (invariant + rule)

This is the *minimum complete set* — every canvas declaration kind that has an unambiguous code-side rendering. Getter/checker/handler are syntactic special cases of function-to-defn; adding them in Phase 7 buys little. update-record-fields is the lone shape-drift refinement; its scope is Sprint 2's compound-shape comparator + Phase 8's delta rendering, so it's structurally a Phase 8 item.

**Push-back on richer set.** If the user wants to ship getter/checker/handler in Phase 7 too, the cost is minimal (each is ~30 LOC of wrapper around `function-to-defn`). Recommendation: ship the 6 cardinal projections in Sprint 3 Tasks 6-11, then if there's slack in Sprint 3, fold getter/checker/handler in as a Task 12. Don't pre-commit to 9.

---

## Open questions for the user

1. **Type sub-dispatch — option A vs B.** Recommend option A (one `defmethod [:clojure :Type]` that branches on `:type/kind` internally). Sprint 3 settles definitively; either option is structurally fine. Push back if you want sub-kind dispatch keys.

2. **Compound-shape rendering rules — placement.** Two homes: a public `render-shape` helper in `src/fukan/canvas/project/core.clj`, OR colocated with `type-to-malli` (the lens-specific renderer). Recommend the core-level helper since multiple projections use it (`type-to-malli`, `event-to-schema`, and any future Layer A projection touching shapes). Push back if you want it lens-localized.

3. **Return-position `:Unit → :nil`.** Where does this swap live? Recommend in `function-to-defn` (post-process return-shape after `render-shape` returns `:any`). Push back if you want it in the alias table.

4. **Invariant fallback for missing `holds-that`.** Recommend Sprint 2 Task 4 adds a fallback to `addr/canonical` (`label → kebab(invariant-name)` when holds-that is absent). Push back if you'd rather have Layer A do the fallback locally.

5. **Stub `ex-info` payload — how much metadata?** Recommend: always `:canvas-id`; for invariants `:invariant-name + :holds-that`; for rules `:rule-name + :when`. The audit trail is load-bearing for Phase 8's "still unimplemented" detection. Push back if you'd rather keep the payload minimal.

6. **Getter/checker/handler — ship in Phase 7 or defer?** Recommend defer to Phase 7.5 to keep Sprint 3 scope tight. Push back if you want them in Phase 7 (cost is low; the question is scope discipline).

7. **Event-schema marker — `^:event` tag.** Recommend marking event schema defs with `^:event` metadata, parallel to fukan's existing `^:schema` convention for record schemas. This lets `source.clj`'s analyzer-direction extraction distinguish events from records (today both are `:data-structure`). Push back if you'd rather wait — it's a small Clojure idiom decision.

8. **Project-lens namespace path.** The Phase 7 plan suggests `src/fukan/canvas/project/`. Confirm; alternative would be `src/fukan/canvas/lens/` (parallel to existing `src/fukan/canvas/lens/` for Phase 5 weigh-tier lenses) but that namespace already exists for a different purpose. Recommend `project/`. Push back if you prefer a different name (the amendment mentioned "project-lens" vs "language-lens" vs "code-spec lens" as candidates).

9. **External-lens registration path.** The pluggability sketch assumes external projects ship lens namespaces alongside their canvas, requiring them at boot. This works for in-repo external projects but not for *runtime* lens injection. Phase 7 doesn't need runtime injection (it's a developer-time tool), but if the user wants lenses loadable from outside the classpath in some future, the registry needs a `(register-lens! ...)` fn. Recommend defer; the static-classpath registration is enough for Phase 7. Push back if you want dynamic registration in Phase 7.

10. **Reuse of `projector/signature-for`.** Recommend lifting from `defn-` to `defn` so Layer A can call it directly. Sprint 2 Task 4 territory. Push back if you'd rather Layer A duplicate the signature-rendering logic (decoupling concern — Layer A's rendering may diverge from the analyzer-side blueprint's needs over time).
