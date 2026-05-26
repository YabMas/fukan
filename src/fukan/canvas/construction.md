# Construction Primitives

Lifts in `fukan.canvas.construction`. Non-opt-out: every project uses these.

## `function`

A synchronous function call with typed inputs, a typed output, and optional effects
or rule couplings. Produces an Affordance with an `:arrow` shape and role
`:fukan.canvas.monolith/exposed-call`.

```clojure
(function "start_server"
  "Start the HTTP server on the given options."
  (takes [opts :ServerOpts])
  (gives (optional :ServerInfo)))
```

```clojure
(function "run"
  "Run the seven sub-phases in fixed order. Returns Phase4Result on success."
  (takes [model :model/Model])
  (gives :Phase4Result)
  (triggers RunPhase4)
  (returns "post.result"))
```

Produces an Affordance with shape `{:kind :arrow :inputs {:kind :record :fields [...]} :outputs ...}`,
role `:fukan.canvas.monolith/exposed-call`, owned by the enclosing module.
`:references` Relations are emitted automatically for every type keyword in the shape.

### Forms

- `(takes [name :Type ...])` — input parameter list. Optional; omit for zero-arg callables.
- `(gives :Type)` — return type shape. Required. Accepts any shape grammar expression.
- `(effect :Name)` — declares a side-effect keyword. Repeatable.
- `(triggers RuleName)` — couples this function to a behavioral rule in the same module. Optional.
- `(returns "label")` — names the return binding for post-conditions. Optional.

### When to use a different lift

- For `() -> Optional<T>` read accessors → use `vocab.lifecycle/getter` (bakes in the zero-arg Optional shape).
- For `(Model) -> [Violation]` validation entry points → use `vocab.validation/checker`.
- For named behavioral commitments without a callable signature → use `vocab.behavioral/invariant`.
- For event-driven reactions → use `vocab.event/handler`.

---

## `record`

A named data type with typed fields. Produces a Type node with `:kind :record`.

```clojure
(record "ServerOpts"
  "HTTP server configuration."
  (field port (optional :Integer)))
```

```clojure
(record "Phase4Result"
  "Non-throwing return envelope from the Phase 4 runner."
  (field model      :model/Model)
  (field violations (list-of :agent/Violation)))
```

### Forms

- `(field name :Type)` — a named field with a shape grammar type. Repeatable. At least one required.

### Notes

Cross-module type references use namespaced keywords: `:model/Model`, `:agent/Violation`.
`record` emits `:references` Relations automatically for ref-typed fields.

---

## `value`

An opaque named type — a named concept whose internal structure is withheld.
Use for types that are meaningful concepts but whose fields are private to
the implementation (e.g. database handles, evaluated binding sets).

```clojure
(value "Stratum"
  "A group of ConstraintRules evaluated together in dependency order.")

(value "Binding"
  "A substitution map from logic-variable Term to bound value.")
```

Produces a Type node with `:kind :atomic`. No fields are declared.

---

## `exports`

Tag the listed declarations as `:exported` — closes the module's API surface.
Without `exports`, every declaration is implicitly visible. With `exports`, only
the named items cross the module wall.

```clojure
(exports ServerOpts ServerInfo)
```

```clojure
(exports Stratum Binding)
```

Must appear **after** the named declarations within `within-module`. Names that
don't match any existing declaration are silently ignored.
