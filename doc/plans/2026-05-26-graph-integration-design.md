# Graph Integration Design — Approach B (Canvas as Model Source)

**Phase 3, Sprint 3, Task 7 — design gate.**

Implementation (Task 8) does not begin until this document is reviewed and approved.

---

## 1. Current State Assessment

### 1.1 Interim pipeline state

`src/fukan/model/pipeline.clj` runs phases 4-6 on an `empty-model`. Confirmed from source:

```clojure
(defn build-model [source-root]
  (let [m1 (build/empty-model)           ; <-- empty; no canvas ingestion
        {:keys [model violations]} (phase4/run m1)
        ...
```

The empty-model shape is:

```clojure
{:primitives {}   :edges []   :tag-defs []   :tag-apps []
 :predicates []   :renderers []   :artifacts {}}
```

Phase 4 structural validation runs against this empty map — it produces no violations because there is nothing to validate. Phase 5 constraint evaluation also produces nothing. Phase 6 (Clojure target analyzer) runs against the empty map, attempts to project spec primitives to code artifacts, and produces no projection edges because `:primitives` is empty.

**Result: the graph viewer renders an empty graph.**

The Allium/Boundary analyzers (phases 1-3) were deleted in Sprint 4. The canvas ports in `canvas/` exist but are not yet wired to the pipeline. Sprint 3 closes this gap.

### 1.2 What the graph viewer expects

The consumer chain is:

```
infra-model/get-model
  → projection/project-model       ; projection.core
  → views.graph/render-graph       ; adds UI selection state
  → cytoscape/graph->cytoscape     ; transforms to JSON
```

**`projection/project-model` input shape** (reads from model):

| Access | Key | Type |
|--------|-----|------|
| `(:primitives model)` | `:primitives` | `Map<String, Primitive>` |
| `(:artifacts model)` | `:artifacts` | `Map<Any, Artifact>` |
| `(:tag-apps model)` | `:tag-apps` | `Vector<TagApplication>` |
| `(:renderers model)` | `:renderers` | `Vector<RendererRegistration>` |
| `(:edges model)` | `:edges` | `Vector<Edge>` |

**`projection/project-model` output shape** (graph projection):

```clojure
{:nodes [{:id     <string>
          :kind   <keyword>         ; e.g. :primitive/container, :artifact/code
          :label  <string>
          :tags   [{:namespace :name}]      ; optional
          :treatment {<string> <any>}       ; optional, from renderers
          :source-location {...}            ; optional, artifacts only
          :selected? <boolean>}]   ; added by render-graph
 :edges [{:id   <string>
          :from <string>            ; node id
          :to   <string>            ; node id
          :kind <keyword>           ; relation kind
          :projection-kind <keyword>    ; optional, :relation/projects edges only
          :validity <keyword>}]}         ; optional, :relation/projects edges only
```

**`views.sidebar/render-sidebar-html` input shape** (reads from model primitive):

```clojure
{:label       <string>
 :id          <string>
 :kind        <keyword>
 :description <string>}    ; optional
```

**Key constraint**: the handler also calls `projection/project-model` for the `/projector` endpoint and reads `(get-in m [:primitives id])` directly for the `/sidebar` endpoint. Both access the same model map structure.

The graph viewer does NOT access the Datascript db directly. It only sees the projected model value that `(infra-model/get-model)` returns.

### 1.3 What the canvas store currently provides

The canvas store (`src/fukan/canvas/core/substrate/store.clj`) is a Datascript db with the following schema attributes:

| Attribute | Type | Notes |
|-----------|------|-------|
| `:entity/id` | unique identity | UUID for all named entities |
| `:entity/type` | indexed | `:Module`, `:Affordance`, `:State`, `:Type` |
| `:entity/name` | indexed | string name |
| `:entity/tag` | many | tag keywords e.g. `:exported` |
| `:affordance/role` | — | keyword e.g. `:canvas/invariant`, `:fukan.canvas.monolith/exposed-call` |
| `:affordance/shape` | — | `pr-str` of the shape map |
| `:affordance/formal-expression` | — | `pr-str` of formal expression |
| `:affordance/doc` | indexed | docstring string |
| `:state/shape` | — | shape value |
| `:module/child` | many ref | ALL child entities (Affordances, States, Types) owned by the Module |
| `:type/doc` | indexed | docstring for Type entities |
| `:references` | many | cross-module refs; value is a keyword (`:model/Model`, `:agent/Violation`, etc.) |

Ownership of all canvas entities (Affordance, State, Type) flows through `:module/child` Relations on the owning Module. There are no back-references (`:affordance/module`, `:state/module`, `:type/module`) on the owned entities — ownership lives exclusively on the owner. The projection layer queries the Module's `:module/child` set to determine what to render as nested under each container node.

The store does NOT currently have attributes for: `:type/kind` (`:atomic` / `:record`), `:type/fields`, `:type/name`. Those are stored on the substrate record but not explicitly indexed in Datascript beyond the generic `:entity/name` and `:entity/type`.

Relations (cross-entity edges) are stored in Datascript as direct attribute assertions on the `from` entity, e.g.:
```
[:db/add [:entity/id <from-uuid>] :references :model/Model]
[:db/add [:entity/id <from-uuid>] :fukan.canvas.monolith/performs :some-effect-keyword]
```

The existing public query API exposes only two functions:
- `(all-modules db)` — returns `[{:name <string>}]`
- `(affordances-in db module-id)` — returns `#{[<name>]}`

All other queries must be added.

### 1.4 The gap

**Shape-level differences between what's emitted and what's consumed:**

| Dimension | Canvas store | Model (expected by viewer) |
|-----------|-------------|---------------------------|
| Entity identity | UUIDs (Datascript eid or `:entity/id` UUID) | string ids in `:primitives` map |
| Node kind vocabulary | `:entity/type` is `:Module`, `:Affordance`, `:State`, `:Type` | `:primitive/*` keywords (`:primitive/container`, `:primitive/rule`, etc.) |
| Label | `:entity/name` (plain string) | `:label` key on primitive |
| Module→child relationship | `:module/child` many-ref on the Module (all entity kinds) | `:children` set on `:primitive/container` |
| Edges | Direct Datascript attribute assertions on `from` entity | `{:from <id> :to <id> :kind <kw>}` vector entries in `:edges` |
| Tags | `:entity/tag` multi-value attribute | `{:namespace :name}` maps in `:tag-apps` |
| Renderers | Not in canvas store at all | `:renderers` vector |
| Artifacts | Not in canvas store | `:artifacts` map |
| Phase 4 violations | Not applicable to canvas content | `:violations` key on model (non-gating) |

The gap is bridgeable. The key mapping decisions are:

- Canvas `:Module` → `:primitive/container` node (modules are the containers-of-design)
- Canvas `:Affordance` → role-dispatched node kind (see §2.4)
- Canvas `:State` → `:primitive/container` node (state shapes are value types)
- Canvas `:Type` → `:primitive/container` node (record/atomic types)
- Canvas `:references` relation → `:relation/uses` edge (declared dependency)
- Other canvas relation kinds → mapped per kind (see §2.4)

---

## 2. Canvas-Source Projection Design

### 2.1 Namespace and entry point

**New file:** `src/fukan/canvas/projection/canvas_source.clj`

**Namespace:** `fukan.canvas.projection.canvas-source`

**Public API:**

```clojure
(defn build-canvas-db
  "Require all canvas namespaces, call their build-canvas fns, merge the
   resulting per-module Datascript dbs into one unified db. Returns a
   Datascript db."
  [] ...)

(defn project
  "Project a unified canvas Datascript db into the model map shape that
   the graph viewer expects. Returns a map conforming to build/Model."
  [db] ...)

(defn build
  "Convenience: build-canvas-db + project in one call."
  [] ...)
```

### 2.2 How files load: require-and-call strategy

**Decision: require all canvas namespaces explicitly; call their `build-canvas` fns.**

Rationale:

1. **REPL correctness.** The require-and-call strategy integrates naturally with clj-reload. When a canvas file changes, clj-reload reloads it, the namespace's `build-canvas` fn is redefined, and the next `(infra-model/refresh-model)` picks up the new implementation. A filesystem-walk approach would have to re-eval files at an arbitrary time, bypassing clj-reload's dependency tracking.

2. **Type safety at the boundary.** Explicitly requiring canvas namespaces turns missing or broken canvas files into load errors at startup, not silent misses.

3. **Consistent with Phase 4 authoring plans.** Phase 4 adds an authoring loop where new canvas files are added interactively. When they exist as real namespaces on the classpath, they participate in the REPL-reload cycle without special handling.

4. **Filesystem walk costs are non-trivial.** Loading `.clj` files by path bypasses the classpath, requires `load-file` or `eval` semantics, and complicates test isolation.

**Implementation sketch:**

```clojure
(ns fukan.canvas.projection.canvas-source
  (:require
    ;; All canvas namespaces — the authoritative registry
    canvas.agent.api
    canvas.agent.edb
    canvas.agent.query
    canvas.agent.sci
    canvas.agent.system
    canvas.agent.views-loader
    canvas.constraint.ast
    canvas.constraint.builtins
    canvas.constraint.derivations
    canvas.constraint.derivations-extra
    canvas.constraint.evaluator
    canvas.constraint.phase5
    canvas.constraint.sort
    canvas.constraint.well-known
    canvas.infra.model
    canvas.infra.server
    ;; ... (all 62 canvas namespaces)
    [fukan.canvas.core.substrate.store :as store]
    [datascript.core :as d]))

(def ^:private canvas-builders
  "Registry of all canvas build-canvas fns."
  [canvas.agent.api/build-canvas
   canvas.agent.edb/build-canvas
   ;; ...
  ])
```

The registry is a plain vector of fn-vars. Adding a new canvas port means adding one line to the registry. This is deliberate: the registry IS the coupling point between the canvas content layer and the pipeline. Making it explicit (rather than discovered by walking the filesystem) forces acknowledgment that a new file has been integrated.

**The registry maintenance burden is low:** 62 files today. Phase 3 does not add more. Phase 4 will add files incrementally; each addition is one line in the registry.

### 2.3 Merging strategy

Each `build-canvas` fn returns a per-module Datascript db (from `(h/with-canvas ...)`) — a fresh immutable db containing only that module's entities and relations.

**Merge mechanism:**

```clojure
(defn- merge-dbs
  "Merge a seq of per-module Datascript dbs into one unified db."
  [dbs]
  (reduce (fn [acc db]
            (let [datoms (d/datoms db :eavt)]
              (d/db-with acc (mapv (fn [d] [:db/add (:e d) (:a d) (:v d)]) datoms))))
          (store/create)
          dbs))
```

**Cross-module reference resolution:**

Cross-module references in canvas ports use namespaced keyword targets, e.g. `:model/Model`, `:agent/Violation`. These are stored in Datascript as values on the `:references` attribute (not as refs to entity ids):

```datascript
[:db/add <affordance-eid> :references :model/Model]
```

The keyword `:model/Model` is NOT resolved to an entity eid at transact time — it stays as a keyword value. This means the merged db contains unresolved cross-module references as keywords.

**Resolution query:** After merging, the projection layer resolves these by querying: "for each `:references` attribute with a keyword value, is there an entity in this db whose `:entity/name` + `:entity/type` matches the keyword's namespace+name pair?"

The keyword naming convention is `:<module-name-kebab>/<entity-name>`. For example, `:model/Model` means: look for an entity with `:entity/name "Model"` inside module `"model.spec"` (or any module with name component `"model"`).

**Resolution strategy:** The projection does NOT need to resolve these to entity eids for the graph viewer to work. The `:references` relation generates a graph EDGE from the affordance to the referenced entity. The edge target is found by name lookup:

```clojure
(d/q '[:find ?target-eid
       :in $ ?target-kw
       :where [?e :entity/name ?n]
              [(= ?n (name ?target-kw))]]
     db target-keyword)
```

When a reference keyword's target does not resolve (e.g. `:model/Model` when `canvas.model.spec` hasn't loaded), the edge is silently dropped — it's a dangling reference, not a build error. This matches the behavior of the old Allium analyzer (which created stub primitives; the projection simply omits the edge).

**Merge key is `:entity/id` (UUID).** Because each entity gets a fresh UUID from `gen-id` at `build-canvas` time, per-module dbs have no eid collisions. The merge appends all datoms from all per-module dbs into the unified db. The only potential collision is `:entity/id` uniqueness — but since UUIDs are generated fresh per build-canvas call, collisions are astronomically unlikely.

**Cross-module refs by module name:** The keyword `:model/Model` uses the module path as namespace (`model`) and entity name as local name (`Model`). The resolution query uses `(name kw)` to get the entity name and `(namespace kw)` to optionally filter by module. In practice, entity names within fukan are unique enough that filtering by name alone is sufficient. If name collisions exist (e.g. two modules both export `"Model"`), the projection takes the first match and emits the edge to it. This is acceptable for Phase 3; Phase 4 may add disambiguation.

### 2.4 Projection: canvas store → graph format

The projection layer transforms canvas Datascript entities into the `{:primitives :edges :tag-defs :tag-apps :predicates :renderers :artifacts}` model map.

**Entity type → graph node kind mapping:**

| Canvas `:entity/type` | `:affordance/role` | Projected `:kind` | Rationale |
|----------------------|-------------------|-------------------|-----------|
| `:Module` | — | `:primitive/container` | Modules are boundary-owning containers in the kernel vocabulary |
| `:Affordance` | `:canvas/invariant` | `:primitive/rule` | Invariants are behavioral commitments — closest kernel primitive is Rule |
| `:Affordance` | `:canvas/rule` | `:primitive/rule` | Reactive rules map directly |
| `:Affordance` | `:canvas/getter` | `:primitive/operation` | Getters are callable operations |
| `:Affordance` | `:canvas/checker` | `:primitive/operation` | Checkers are operations |
| `:Affordance` | `:fukan.canvas.monolith/exposed-call` | `:primitive/operation` | Exposed functions are operations |
| `:Affordance` | `nil` / other | `:primitive/operation` | Default fallback |
| `:State` | — | `:primitive/container` | State shapes are value-type containers |
| `:Type` (`:record`) | — | `:primitive/container` | Record types are containers with fields |
| `:Type` (`:atomic`) | — | `:primitive/container` | Atomic types are opaque containers |

**Primitive id generation:** Canvas entities have UUID `:entity/id` values. These do NOT work as `:primitives` map keys (which are strings in the existing model). The projection converts UUID to string: `(str uuid)`. This is stable across builds (UUIDs are generated once at `build-canvas` time and survive reload via clj-reload if the namespace is cached).

**WARNING — UUID stability:** UUIDs are generated via `gen-id` → `(random-uuid)` at `build-canvas` call time. Every call to `build-canvas` generates new UUIDs. This means: every `(infra-model/refresh-model)` call produces different node ids in the graph. The graph viewer uses node ids for selection state (which node is currently selected). After a refresh, the selected id will no longer match any node.

This is acceptable for Phase 3 (the graph viewer has no persistence; selection state is ephemeral per-request). Phase 4 authoring will need stable ids; that requires either deterministic id generation (hash of module-name+entity-name) or an id-stabilization pass in the projection. **This is an open question for Task 8 to decide** — see §7.

**Projected primitive structure for a Module:**

```clojure
{<uuid-string> {:kind  :primitive/container
                :id    <uuid-string>
                :label <entity/name>}}
```

**Projected primitive structure for an Affordance (function/invariant/rule/getter):**

```clojure
{<uuid-string> {:kind        :primitive/rule     ; or :primitive/operation
                :id          <uuid-string>
                :label       <entity/name>
                :description <affordance/doc>}}   ; optional
```

**Parent-child relationship (Module → all owned entities):**

The kernel `:primitive/container` has a `:children` set. To make the graph viewer render Affordances, States, and Types inside their Modules, the projection queries the Module's `:module/child` set — which now uniformly covers all three entity kinds.

Query to build children map (single query for ALL child kinds):

```clojure
(d/q '[:find ?module-id ?child-id
       :where
       [?module :module/child ?child]
       [?module :entity/id ?module-id]
       [?child :entity/id ?child-id]]
     db)
```

This replaces the old per-kind queries (`:affordance/module`, `:state/module`). Types are now module-owned via the same `:module/child` mechanism — no separate `:type/module` attribute needed.

**Edge generation from canvas Relations:**

Canvas Relations are stored as direct Datascript attribute assertions on the `from` entity (not as separate entities). To enumerate all relations:

```clojure
(defn- all-relation-datoms [db]
  ;; Attributes that represent cross-entity relations (not structural attrs)
  (let [structural-attrs #{:entity/id :entity/type :entity/name :entity/tag
                           :affordance/role :affordance/shape
                           :affordance/formal-expression :affordance/doc
                           :state/shape :module/child
                           :type/doc :references}]
    (d/datoms db :aevt)))
```

Simpler approach: maintain an explicit set of known relation attribute keywords and query for each. For Phase 3, the relevant relation attributes are:

- `:references` — emitted by `construction/emit-refs!` and checkers; maps to `:relation/uses`
- `:fukan.canvas.monolith/performs` — emitted for `effect:` clauses; maps to `:relation/uses` (or a custom kind)
- Any other relation kinds emitted by vocab lifts

**Relation kind mapping:**

| Canvas relation attribute | Projected `:kind` |
|--------------------------|-------------------|
| `:references` | `:relation/uses` |
| `:fukan.canvas.monolith/performs` | `:relation/uses` |
| Any other keyword | keyword as-is |

For Phase 3, emit `:relation/uses` for `:references` and preserve all other keywords directly. The graph viewer will render any relation kind; the `kind` value is used as an edge label.

**Edge structure:**

```clojure
{:id   (str "e" idx)
 :from <from-entity-uuid-string>
 :to   <to-entity-uuid-string>   ; may be nil if target not found
 :kind :relation/uses}
```

Edges where `:to` cannot be resolved (dangling keyword reference) are dropped.

**Tags:**

Canvas `:entity/tag` values are keywords (e.g. `:exported`). The existing model `:tag-apps` uses `{:case :target/primitive :id <id>}` target + `{:namespace :name}` tag structure. For Phase 3, canvas tags are projected as tag-applications with namespace derived from the tag keyword's namespace and name from its local name. Tags with no namespace (e.g. `:exported`) get namespace `"canvas"`.

**Renderers and predicates:**

Canvas store does not contain renderer or predicate registrations. The projected model will have `:renderers []` and `:predicates []`. Renderers and predicates come from the Phase 5 defaults registration (which runs after phase4). This is fine — the graph viewer renders without treatments when no renderer registrations exist; nodes just show without visual vocabulary overlays.

**Artifacts:**

Canvas store does not contain artifacts. The projected model will have `:artifacts {}`. Phase 6 (Clojure target analyzer) runs after the canvas projection and adds artifacts by walking the source tree.

**Full projection fn sketch:**

```clojure
(defn project [db]
  (let [modules     (project-modules db)        ; {id -> primitive}
        affordances (project-affordances db)    ; {id -> primitive}
        states      (project-states db)         ; {id -> primitive}
        types       (project-types db)          ; {id -> primitive}
        primitives  (merge modules affordances states types)
        children    (build-children-map db)     ; {module-id -> #{child-id ...}}
        primitives  (add-children primitives children)
        edges       (project-edges db primitives)
        tag-apps    (project-tag-apps db)]
    {:primitives primitives
     :edges      (vec (map-indexed #(assoc %2 :id (str "e" %1)) edges))
     :tag-defs   []
     :tag-apps   tag-apps
     :predicates []
     :renderers  []
     :artifacts  {}}))
```

---

## 3. Pipeline Integration

### 3.1 How `pipeline.clj` changes

The pipeline becomes a two-phase function:

1. **Phase 0 (new): canvas ingestion.** Call `canvas-source/build` to produce a projected model map from all canvas ports.
2. **Phases 4-6 (existing): validation and artifact projection.** Run on the canvas-projected model.

```clojure
(defn build-model [source-root]
  (let [m0 (canvas-source/build)                      ; Phase 0: canvas ingestion
        {:keys [model violations]} (phase4/run m0)    ; Phase 4: structural validation
        m2 (-> model (assoc :violations violations) register-defaults)
        m3 (phase5/run m2)]                            ; Phase 5: constraint evaluation
    (clj-analyzer/run m3 (project-defaults/fukan-on-fukan) source-root)))  ; Phase 6
```

The `source-root` parameter is still passed to Phase 6 (the Clojure analyzer needs it to walk `.clj` files). It is NOT passed to `canvas-source/build` — that function discovers canvas ports via the explicit require registry.

**The `empty-model` in `build.clj` is no longer the pipeline's starting point.** Phase 0 produces the initial populated model. However, `build/empty-model` remains as a utility function (used in tests, used internally in canvas-source for the initial state before projection). It is NOT removed.

### 3.2 What happens to `empty-model`

`build/empty-model` stays. Its role changes:

- **Before:** the starting point for `build-model`
- **After:** the starting point for `canvas-source/project`, which adds canvas entities onto it; also used directly in tests for isolated model construction

No changes to `build.clj` except removing the `empty-model` call from `build-model` (it moves into `canvas-source/project`).

### 3.3 Whether phases 4-6 still apply

**Yes, phases 4-6 still apply.** They operate on the model map regardless of how it was built.

- **Phase 4** validates structural consistency of whatever primitives and edges are in the model. With canvas-sourced primitives, it will validate them. Most Phase 4 rules check the legacy kernel primitive structure (Container, Actor, Rule, etc.) — after projection, canvas Affordances appear as `:primitive/operation` or `:primitive/rule` with minimal substrate, so most Phase 4 rules will find nothing to complain about. This is acceptable; Phase 4 rules may eventually be adapted to validate canvas-specific structural constraints (e.g. "every Module with exported affordances has a corresponding implementation namespace"), but that is Phase 4 work.

- **Phase 5** evaluates constraint predicates. With `:predicates []` initially (canvas store doesn't populate predicates), Phase 5 produces no violations. After the defaults registration, the two well-known constraints (`signal_gap`, `external_must_have_wrapper`) run against the canvas-projected primitives. They may produce warnings; this is acceptable.

- **Phase 6** (Clojure target analyzer) walks source `.clj` files and projects spec primitives to code artifacts. After Phase 3, the spec primitives are canvas-sourced. The Clojure analyzer matches spec primitive ids against code entity coordinates. This matching will likely produce mostly `:absent` drift edges (no code artifacts match the new UUID-string ids). **This is expected and acceptable for Phase 3.** Phase 4 will wire the Clojure analyzer to the canvas id scheme.

---

## 4. Lifecycle Integration

### 4.1 `infra/model.clj` changes

The `load-model` function currently takes a `src` path (for Phase 6 source walking). This remains its signature.

```clojure
(defn load-model [src]
  (println "Loading model from canvas + src:" src)
  (let [m (pipeline/build-model src)]
    (reset! state {:model m :src src})
    ...))
```

No signature change. The pipeline internals change (canvas ingestion added), but the infra lifecycle sees the same interface.

**The `@state` atom** tracks `{:model <model-map> :src <string>}`. This continues unchanged. The canvas store is NOT persisted in the `@state` atom — it is rebuilt on each load/refresh. This is deliberate: the canvas store is a build artifact, not live state.

**`refresh-model`** calls `load-model` with the stored `:src`. After the change, this rebuilds the canvas store from scratch (requires all canvas namespaces again, calls all `build-canvas` fns). Since clj-reload has already reloaded changed canvas namespace code, the `build-canvas` fns on the canvas namespaces reflect the latest content. The resulting canvas store reflects the latest canvas content.

### 4.2 REPL workflow

The existing `(refresh)` in `dev/user.clj` does:

```clojure
(defn refresh []
  (reload-code!)           ; clj-reload picks up changed .clj files
  (infra-model/refresh-model))   ; rebuilds model
```

`reload-code!` uses `(reload/reload {:only :loaded})`. Canvas files are on the classpath (in `canvas/` which is in `deps.edn` `:paths`). If a canvas file is changed on disk, clj-reload detects the timestamp change and reloads the namespace. When `refresh-model` then calls `pipeline/build-model`, which calls `canvas-source/build`, which calls all registered `build-canvas` fns — the reloaded namespace's `build-canvas` fn is invoked, producing updated content.

**This is automatic.** No changes to `dev/user.clj` are needed. The existing `(refresh)` workflow handles canvas file changes identically to implementation file changes.

**Edge case: new canvas file added.** If a new canvas file is created during development (Phase 4 authoring), it must be added to the `canvas-source` registry. The developer adds the require + registry entry, then calls `(reset)` (not `(refresh)`) to pick up the new namespace. This is documented as part of the canvas authoring workflow.

### 4.3 `defonce` atoms

The `defonce ^:private state` atom in `infra/model.clj` survives clj-reload. It continues to hold the projected model snapshot. No changes needed. The canvas store is ephemeral (rebuilt each time); only the projected model map is cached in the atom.

---

## 5. Per-Request Guarantees

### 5.1 Handler behavior

The handler fetches model per-request via `(infra-model/get-model)`. This returns the model snapshot from the `@state` atom — an immutable map. The canvas store used to build it is not cached in the atom; it was ephemeral during the load/refresh call.

**No changes to `handler.clj` are needed.** The handler is unaware of whether the model came from legacy Allium analyzers or from the canvas store.

The `/sidebar` endpoint reads `(get-in m [:primitives id])`. After the change, primitive ids are UUID strings. Sidebar calls require the UI to pass a valid UUID string as the `id` query param. The graph viewer already constructs sidebar calls from node ids it received from `/graph`, so the ids will be consistent within a session.

### 5.2 Caching

The canvas projection is one-shot: built once on `load-model` / `refresh-model`, cached in the `@state` atom. No per-request rebuilding. The Datascript query cost for projecting 62 canvas ports into a model map is O(n) in the number of entities, expected to be a few milliseconds — negligible.

**No caching layer is needed at the canvas-source level.** The `@state` atom in `infra/model.clj` IS the cache. The cached value is the projected model map, not the Datascript db.

### 5.3 Nothing in handler routes changes

Confirmed: handler routes are model-source-agnostic. They call `(infra-model/get-model)` and pass the result to projection/views. The change from Allium-sourced primitives to canvas-sourced primitives is invisible to handler routes.

---

## 6. Test Strategy

### 6.1 Existing tests that must continue to pass

- **Canvas suite** (62 canvas ports × ~3 assertions each = ~189 tests): assert each port produces a non-empty store with key entity names. These are unaffected by Sprint 3.
- **Phase 4 validation tests**: operate on model maps directly; unaffected if the model map shape is preserved.
- **Phase 5 constraint tests**: same.
- **Projection tests** (if any exist for `projection.core`): these use handcrafted model maps; unaffected.

### 6.2 New tests needed

**`test/fukan/canvas/projection/canvas_source_test.clj`**

Three test groups:

1. **`build-canvas-db` produces a populated Datascript db:**
   ```clojure
   (deftest build-canvas-db-test
     (let [db (canvas-source/build-canvas-db)]
       (is (seq (store/all-modules db)))
       (is (> (count (store/all-modules db)) 50))  ; 62 canvas ports, each has ≥1 module
       (is (some #(= "infra.server" (:name %)) (store/all-modules db)))))
   ```

2. **`project` produces a model map with expected structure:**
   ```clojure
   (deftest project-test
     (let [model (canvas-source/build)]
       (is (map? (:primitives model)))
       (is (vector? (:edges model)))
       (is (pos? (count (:primitives model))))
       ;; Confirm a known entity is present
       (is (some #(= "infra.server" (:label %)) (vals (:primitives model))))))
   ```

3. **Single-module projection (isolation test):**
   ```clojure
   (deftest single-module-projection-test
     (let [db (canvas.infra.server/build-canvas)
           model (canvas-source/project db)]
       (is (= 1 (count (filter #(= :primitive/container (:kind %))
                               (vals (:primitives model))))))
       ;; infra.server has: ServerOpts, ServerInfo (records), start_server, stop_server,
       ;; get_port, SingleServerInstance = 6 affordances + 1 module
       (is (= 7 (count (:primitives model))))))
   ```

4. **Edges are generated for `:references` relations:**
   ```clojure
   (deftest references-edge-test
     (let [model (canvas-source/build)]
       ;; canvas.infra.model has references to :model/Model
       (is (some #(= :relation/uses (:kind %)) (:edges model)))))
   ```

### 6.3 Existing tests after Sprint 3

After the change, `(pipeline/build-model src)` produces a populated model instead of an empty one. Any test that calls `build-model` and asserts the result is empty will fail. Search for such tests before Task 8 begins.

Phase 4/5/6 tests that call pipeline functions with crafted model maps pass the model in directly (not via `build-model`), so they are unaffected.

---

## 7. Edge Cases and Open Questions

### 7.1 Cross-canvas-port references

Example: `canvas/infra/model.clj` references `:model/Model`. The canvas store for that port contains:

```datascript
[:db/add <load_model-affordance-eid> :references :model/Model]
```

The keyword `:model/Model` means: "the entity named `Model` inside a module whose path contains `model`." After merging all 62 canvas dbs, the unified db contains an entity from `canvas/model/spec.clj` whose `:entity/name` is `"Model"` and whose `:entity/type` is `:Type`.

The resolution query:

```clojure
(d/q '[:find ?eid
       :in $ ?target-name
       :where [?eid :entity/name ?target-name]]
     db "Model")
```

Returns the eid of the `Model` entity from `canvas.model.spec`. The edge is then:

```
{:from <load_model-affordance-uuid>
 :to   <model-spec-Model-uuid>
 :kind :relation/uses}
```

**Name collision risk:** if two modules both have an entity named `"Model"`, the resolution picks the first one. In fukan's canvas corpus, `"Model"` is defined only in `canvas/model/spec.clj`. The risk is low for fukan-itself. Future projects using fukan may have collisions; Phase 4 can add namespace-qualified resolution.

**Unresolvable references (keyword whose name doesn't match any entity):** silently dropped. The edge is omitted. This is the same behavior as the legacy Allium analyzer's stub-creation mechanism — the stub would appear in the model but contribute no visible content. Omission is cleaner.

### 7.2 UUID stability across refreshes

**Problem:** Each `build-canvas` call generates new UUIDs. After `(refresh)`, all node ids change. The graph viewer's selection state (which node is currently selected via `selected-id`) becomes stale.

**Phase 3 impact:** The `/graph` endpoint returns `{:selected-id nil}` by default (the handler passes `{:selected-id nil}`). Selection state is not currently persisted between requests. So UUID churn on refresh does not break the UI in Phase 3 — the graph just re-renders with new ids and no selection.

**Phase 4 impact:** If Phase 4 adds persistent selection (URL-based, or via browser state), UUID churn on refresh will break selection restoration. The fix is deterministic id generation.

**Recommended fix (implement in Task 8, not deferred):** Generate UUIDs deterministically from `(module-name + entity-name)` using a UUID v5 (name-based UUID) function, or simply use a stable string id like `(str module-name "/" entity-name)`. The latter is simpler and more readable in the graph viewer.

**Proposed stable id scheme:**

- Module: `module-name` (e.g. `"infra.server"`)
- Affordance: `module-name "/" affordance-name` (e.g. `"infra.server/start_server"`)
- State: `module-name "/state/" state-name`
- Type: `module-name "/type/" type-name` (or just `type-name` if globally unique)

This requires the projection layer to build ids from names, not from Datascript eids. The Datascript store still uses random UUIDs for internal entity identity (that's the substrate's choice); the projection layer computes stable string ids for the graph viewer's consumption. **This is the recommended approach** and Task 8 should implement it.

**Open question 1 for user:** Should the stable id scheme use `"/"` as separator (e.g. `"infra.server/start_server"`) or a different convention? The choice affects graph viewer display (id appears as node id in hover tooltips) and URL-based selection in Phase 4.

### 7.3 Types are module-owned (resolved: Option B, Task 7.5)

**Decision (user, Sprint 3 Task 7.5):** Types are module-owned — they appear as children of the module they are defined in, not as top-level floating nodes.

**Implementation (Sprint 3 Task 7.5):** The substrate refactor implements this via `:module/child` on the owner, not via a `:type/module` back-reference on the owned entity. The substrate's principle — "the owner tracks its children, not vice versa" — is now applied uniformly to all three entity kinds:

- **Affordance**: no `:affordance/module` field. Ownership via `:module/child` from the Module.
- **State**: no `:state/module` field. Ownership via `:module/child` from the Module.
- **Type**: no `:type/module` field. Ownership via `:module/child` from the Module.

The `declare-affordance`, `declare-state`, and `declare-type` helpers in `helpers.clj` emit `:module/child` datoms automatically when called inside `within-module`. The `record` and `value` lifts in `construction.clj` route through `declare-type`, inheriting this behavior.

**Task 8 impact:** The projection queries `:module/child` to determine parent/child relationships for ALL canvas entity kinds, uniformly — one query instead of three per-kind queries. The old `:affordance/module` and `:state/module` schema attributes no longer exist. No `:type/module` schema entry is needed.

### 7.4 Empty canvas store on first load

If `build-canvas-db` produces an empty db (e.g. all canvas namespaces fail to load), `canvas-source/project` returns a model with empty `:primitives`. The graph viewer renders an empty graph. The pipeline continues without error (Phase 4 validates nothing, Phase 5 evaluates nothing). This is identical to the current interim state. No special handling needed.

If individual canvas namespaces fail to load (e.g. compilation error in one port), the `require` in `canvas_source.clj`'s ns declaration will throw at load time. This surfaces as a startup error, which is the correct behavior (broken spec = broken startup).

### 7.5 Hot-reload semantics during REPL development

When developing a canvas port iteratively:

1. Developer edits `canvas/infra/server.clj`
2. `(refresh)` calls `reload-code!` → clj-reload detects timestamp change → reloads `canvas.infra.server`
3. `(infra-model/refresh-model)` → calls `pipeline/build-model` → calls `canvas-source/build` → calls all registered `build-canvas` fns including the reloaded `canvas.infra.server/build-canvas`
4. The model atom is updated with the new projected model
5. Next browser request sees the updated graph

**The REPL cycle is:** edit canvas file → `(refresh)` → browser refresh. This is identical to the existing dev cycle for implementation files.

**One-shot vs live projection:** The projection is one-shot (rebuild on explicit `(refresh)`, not recomputed on each request). This is correct. Per-request recomputation would defeat the `@state` atom caching and add latency to every graph request. The trade-off (stale graph until explicit `(refresh)`) is correct for a development tool.

---

## 8. Implementation Plan for Task 8

### 8.1 Files to create

```
src/fukan/canvas/projection/canvas_source.clj
  — canvas-source namespace
  — require registry of all 62 canvas namespaces
  — build-canvas-db, project, build

test/fukan/canvas/projection/canvas_source_test.clj
  — tests: build-canvas-db, project, single-module projection, references edges
```

### 8.2 Files to modify

```
src/fukan/model/pipeline.clj
  — add (require [fukan.canvas.projection.canvas-source :as canvas-source])
  — in build-model: replace (build/empty-model) with (canvas-source/build)
  — remove the now-unused build/empty-model call
  — update docstring to mention Phase 0 canvas ingestion

src/fukan/canvas/core/substrate/store.clj
  — add query helpers needed by canvas-source projection:
      all-affordances, all-states, all-types, all-relations
  — NOTE: :affordance/module, :state/module, :type/module schema entries no longer
    exist (removed in Sprint 3 Task 7.5); children-of-module is already present
  — (alternatively, these can live directly in canvas_source.clj;
     prefer store.clj if they are general-purpose queries)

src/fukan/infra/model.clj
  — NO signature changes
  — update load-model docstring to mention canvas ingestion
  — (no code changes needed if pipeline.clj encapsulates the canvas source)
```

**`build.clj` changes are minimal:**

`empty-model` stays as-is. Its usage in `build-model` is removed; it remains available for test helpers and `canvas-source/project` initialization.

If Phase 4 rule sub-phases validate structure that canvas-projected primitives don't have (e.g. required `:label` field), `make-container`-style constructors may need defaults. Investigate during Task 8 Step 1 (run Phase 4 against a sample canvas-projected model; check for unexpected errors).

### 8.3 Test files needed

```
test/fukan/canvas/projection/canvas_source_test.clj   (new)
```

No changes to existing test files unless Phase 4 tests assert on empty-model behavior.

### 8.4 Commit pattern

Task 8 is one logical change with three substeps. Commit pattern:

```
jj new
jj desc -m "feat(canvas/projection): canvas-source — build unified db from all canvas ports"
# Implement build-canvas-db only; tests pass for that fn

jj new
jj desc -m "feat(canvas/projection): canvas-source — project canvas db to model map"
# Implement project; single-module projection tests pass

jj new
jj desc -m "feat(canvas/pipeline): route canvas-source as Phase 0 input to build-model"
# Wire into pipeline; full integration tests pass; browser shows populated graph
```

Three commits. If the stable-id scheme (§7.2) is implemented as recommended, it goes into the second commit (it's part of `project`).

### 8.5 Verification criteria for Task 8 completion

- Full test suite passes (canvas suite 189+ tests + new canvas-source tests).
- `(go {})` in the REPL starts the server; `(status)` shows non-zero primitive count.
- Browser opens `/graph`; the graph renders with module nodes and affordance nodes.
- `(refresh)` in the REPL after editing a canvas file causes the graph to update on next browser refresh.
- Phase 4/5/6 run without throwing (violations may be generated; that's acceptable).

---

## Appendix: Key design decisions settled by this document

1. **Load strategy: require-and-call over filesystem walk.** Explicit registry in `canvas_source.clj`. Integrates with clj-reload; fails fast on broken namespaces; makes coupling visible.

2. **Stable string ids for graph nodes.** Deterministic `module-name/entity-name` ids instead of random UUIDs. Survives refresh; required for Phase 4 authoring loop.

3. **Canvas store is ephemeral; only projected model is cached.** The `@state` atom holds the projected model map. The Datascript db is rebuilt on each load/refresh and discarded. No second cache layer needed.

4. **Canvas entity type → kernel primitive kind mapping.** Module → `:primitive/container`; Affordance (role-dispatched) → `:primitive/rule` or `:primitive/operation`; State and Type → `:primitive/container`. Enables the existing graph viewer (which understands only kernel primitive kinds) to render canvas content without modification.

5. **Types are module-owned via `:module/child`.** Sprint 3 Task 7.5 added uniform module ownership for Affordance, State, and Type via `:module/child` on the owner (Option B). No back-references (`:type/module`, `:affordance/module`, `:state/module`) exist on owned entities. The projection queries `:module/child` uniformly for all child kinds.

6. **Phases 4-6 run unchanged on the canvas-projected model.** Phase 4 validates (mostly silently on the new primitive shape). Phase 5 evaluates well-known constraints. Phase 6 projects spec primitives to code artifacts (mostly `:absent` edges in Phase 3; will be wired correctly in Phase 4).
