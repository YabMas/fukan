# Lifecycle Vocabulary

Lift in `fukan.canvas.vocab.lifecycle`. Opt-in: require this namespace only if
your project models stateful modules that expose optional read accessors.

Ships one lift: `getter`.

---

## `getter`

A zero-argument `Optional<T>` accessor. The shape is baked in — a getter is
exactly `() -> Optional<T>`. The lift takes name, docstring, and the inner
return type. The full arrow shape and any `:references` Relations are emitted
automatically.

```clojure
(getter "get_port"
  "Return the port the server is listening on."
  :Integer)
```

```clojure
(getter "get_model"
  "Return the currently loaded structural model, or nil if not yet built."
  :model/Model)
```

Produces an Affordance with:
- role `:canvas/getter`
- shape `() -> Optional<T>` where `T` is the provided type expression
- `:references` Relations for any ref types in `T` (auto-emitted)

### Baked-in shape (expanded)

```
{:kind :arrow
 :inputs  {:kind :record :fields []}
 :outputs {:kind :optional :inner <your-type>}}
```

The inner type can be any shape grammar expression:

```clojure
(getter "get_active_session" "Current session." :session/Session)
(getter "get_config"         "Current config."  :config/Config)
```

### When to use a different lift

- Getter that takes parameters → use `function`.
- Non-optional return → use `function` with `(gives :T)`.
- Blocking/async read with effects → use `function` with `(effect ...)`.

### Pattern: getter + state

Getters typically pair with a `state` declaration (when using the raw substrate API)
or simply with a docstring that names the state they expose:

```clojure
(h/within-module "infra.server"
  ;; ...
  (getter "get_port"
    "The port the HTTP server is bound to, once started."
    :Integer))
```
