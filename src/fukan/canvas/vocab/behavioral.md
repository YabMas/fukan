# Behavioral Vocabulary

Lifts in `fukan.canvas.vocab.behavioral`. Opt-in: require this namespace only if
your project explicitly models named behavioral commitments.

Ships two lifts: `invariant` (timeless commitments) and `rule` (reactive declarations
triggered by named events or entry points). Both produce no-shape Affordances —
they carry semantics via `:affordance/formal-expression`, not an arrow shape.

---

## `invariant`

A named timeless commitment of the enclosing module. Maps to Allium `invariant`
and `guarantee` declarations. The semantic distinction (consumer-facing guarantee
vs internal invariant) is carried by the name convention (`<Thing>Guarantee` vs
`<Thing>Invariant`), not by a separate lift.

```clojure
(invariant "SingleServerInstance"
  "At most one HTTP server runs at a time."
  (holds-that "at-most-one server is active"))
```

```clojure
(invariant "PurelyDerivedFromModel"
  "The aggregated Violation sequence is a pure function of the input Model
   and registered sub-phase rule modules. No global mutable state or I/O."
  (holds-that "purely-derived-from-model"))
```

Produces an Affordance with role `:canvas/invariant`. The `holds-that` string
is stored as `:affordance/formal-expression` — a prose key identifying the
commitment for queries and constraint checks.

### Forms

- `(holds-that "prose-key")` — short prose or a kebab-slug naming the commitment. Required.

### When not to use `invariant`

- For reactive "fires when X happens" semantics → use `rule`.
- For callable entry points with typed signatures → use `function`.

---

## `rule`

A reactive behavioral declaration: describes what the system does when a named
trigger pattern matches. Maps to Allium `rule X { when: X(params) }`.

```clojure
(rule "RunPhase4"
  "Phase 4 entry point — fired by the build pipeline after Phases 1-3."
  (when RunPhase4 (model :model/Model)))
```

```clojure
(rule "AnalyzeFile"
  "Walk one .allium file's declarations and fold kernel content into the model."
  (when AnalyzeFile
    (model      :model/Model)
    (ast        :parser/ParsedAllium)
    (coordinate :String)
    (use_aliases (optional (map-of :String :String)))))
```

Produces an Affordance with role `:canvas/rule`. The `when` form is stored as
`{:when [...params]}` in `:affordance/formal-expression`.

### Forms

- `(when TriggerName (param :Type) ...)` — trigger signature. Required.
  `TriggerName` is an unquoted symbol matching the rule's canonical name.
  Each `(param :Type)` pair names an input binding.

### Coupling to `function`

A `function` can couple to a rule via `(triggers RuleName)`:

```clojure
(function "run"
  "Run the validation pipeline."
  (takes [model :model/Model])
  (gives :Phase4Result)
  (triggers RunPhase4))      ;; <-- couples to the rule above
```

This emits a `:triggers` Relation from the Affordance to the rule entity.
