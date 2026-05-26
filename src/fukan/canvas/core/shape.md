# Shape Expression Grammar

Used in `fukan.canvas.core.shape/parse`. Appears in `(gives ...)`, `(takes [...])`,
`(field ...)`, and `getter`'s return-type argument.

---

## Atomic type

A bare keyword without a namespace — names a local or primitive type.

```clojure
:String
:Integer
:Double
:Unit
:ServerOpts
```

Produces `{:kind :atomic :name :TypeName}`.

---

## Namespaced ref

A namespaced keyword — references a type defined in another module.

```clojure
:model/Model
:agent/Violation
:parser/ParsedAllium
:event-driven.cart/LineItem
```

Produces `{:kind :ref :target :module/Name}`.
A `:references` Relation is emitted from the enclosing entity to the target.

---

## `optional`

A value that may be absent.

```clojure
(optional :Integer)
(optional :ServerInfo)
(optional (list-of :Any))
```

Produces `{:kind :optional :inner <inner-shape>}`.

---

## `list-of`

An ordered sequence of a uniform element type.

```clojure
(list-of :LineItem)
(list-of :agent/Violation)
(list-of :ast/ConstraintRule)
```

Produces `{:kind :list :elem <elem-shape>}`.

---

## `set-of`

An unordered collection of a uniform element type, with no duplicates.

```clojure
(set-of :Binding)
(set-of :String)
```

Produces `{:kind :set :elem <elem-shape>}`.

---

## `map-of`

A keyed map from one type to another.

```clojure
(map-of :String :String)
(map-of :String (optional :String))
```

Produces `{:kind :map :key <key-shape> :val <val-shape>}`.

---

## `sum-of`

A discriminated union — one of several possible types.

```clojure
(sum-of :String :Integer :Nil)
```

Produces `{:kind :sum :variants [<shape> ...]}`.

---

## `tuple-of`

A fixed-length product type with positionally typed elements.

```clojure
(tuple-of :String :Integer)
(tuple-of :model/Model :agent/Violation)
```

Produces `{:kind :tuple :elems [<shape> ...]}`.

---

## `record-of`

An anonymous inline record — a named-field product type without a `record` declaration.
Use for one-off inline shapes; for reusable types prefer a top-level `record` declaration.

```clojure
(record-of [id :String] [value :Integer])
```

Produces `{:kind :record :fields [["id" <shape>] ["value" <shape>]]}`.

---

## `ref-to`

An explicit reference to a namespaced keyword type. Equivalent to writing the
namespaced keyword directly; useful when the target keyword is held in a variable
or when clarity requires the explicit form.

```clojure
(ref-to :model/Model)
```

Produces `{:kind :ref :target :model/Model}`.

---

## Nesting

Shapes compose freely:

```clojure
;; Optional list of cross-module refs
(optional (list-of :Integer))

;; Map from string to optional list
(map-of :String (optional (list-of :Any)))
```

The shape parser recurses into every position, so any expression can appear as
the inner type of `optional`, the elem of `list-of`, etc.
