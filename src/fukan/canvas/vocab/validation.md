# Validation Vocabulary

Lift in `fukan.canvas.vocab.validation`. Opt-in: require this namespace only if
your project structures validation as `(Model) -> [Violation]` entry points.

Ships one lift: `checker`.

---

## `checker`

A validation entry point with the fully baked-in signature `(Model) -> [Violation]`.
Checkers take exactly one argument — the model — and return a possibly-empty list
of violations. The shape and `:references` Relations are emitted automatically; the
lift takes only name and docstring.

```clojure
(checker "rules_4a"
  "Run sub-phase 4a against the model.")
```

```clojure
(checker "check"
  "Run all five 4a composition rules against the Model and return
   the aggregated Violation sequence.")
```

Produces an Affordance with:
- role `:canvas/checker`
- shape `(model: Model) -> [Violation]` (arrow with one input record field and list output)
- `:references` Relations to `:model/Model` and `:agent/Violation` (auto-emitted)

### Baked-in shape

```
{:kind :arrow
 :inputs  {:kind :record
            :fields [["model" {:kind :ref :target :model/Model}]]}
 :outputs {:kind :list
            :elem {:kind :ref :target :agent/Violation}}}
```

The shape is constant — you cannot vary it. If your check has a different signature
(extra parameters, a different return type), declare it as `function` instead.

### When to use a different lift

- Different signature (e.g. `(Model, Config) -> [Violation]`) → use `function`.
- A named behavioral constraint without a callable signature → use `vocab.behavioral/invariant`.
- A pure accessor → use `vocab.lifecycle/getter`.

### Pattern: checker + rule

For sub-phase checkers that are both callable and triggered by the pipeline rule,
use a `checker` alongside a `rule` and couple the runner `function` to the rule:

```clojure
(rule "RunPhase4"
  "Phase 4 entry point."
  (when RunPhase4 (model :model/Model)))

(function "run"
  "Run the seven sub-phases in fixed order."
  (takes [model :model/Model])
  (gives :Phase4Result)
  (triggers RunPhase4))

(checker "rules_4a" "Run sub-phase 4a against the model.")
(checker "rules_4b" "Run sub-phase 4b against the model.")
;; ... through 4g
```
