# shop — the reference

A small system written the one way a project in this family is meant to be. It exists to be
copied from and ported to: when fukan reports something about another project that it does not
report here, the difference is that project's distance from this one.

## The one idea

Every shape that flows through the system has a name in the domain, and every signature is
written in those names and in nothing else.

| | named by | example | the name's namespace |
|---|---|---|---|
| where one value passes | an **attribute** | `:order/id` | its entity or value shape |
| where a shape passes | a **view** | `:checkout/receipt` | the use that takes it |

A name is a qualified keyword and the keyword is the whole name. So a signature is plain data,
spelled identically in three places:

```clojure
;; the domain declares the words            src/shop/domain/order.clj, src/shop/checkout.clj
(lexicon/views domain/vocabulary {:checkout/receipt [:order/id :order/total :customer/name]})

;; the code is written in them               src/shop/checkout.clj
(mx/defn receipt :- [:maybe :checkout/receipt] [id :- :order/id] …)

;; the design says the same, in the same words   design/checkout.clj
(Operation receipt {:signature [:=> [:cat :order/id] [:maybe :checkout/receipt]]})
```

## What holds it together

- `clojure -M:test` — the code runs with every signature enforced by Malli: a value that is not
  what the word says is refused where it passes.
- `clojure -M:fukan -m fukan.cli check --src src --spec-dirs design --format text` — fukan reads
  the domain, the code and the design with no configuration, and checks that the design and the
  code say the same thing.

## Rules it keeps

1. The vocabulary is one `lexicon/vocabulary` over a literal vector of shapes.
2. A view is declared, by name, in the namespace that takes it: `lexicon/views` over a literal
   map, handed to `domain/install!` before the namespace's first function.
3. A function is a `malli.experimental/defn`, annotated in full.
4. A signature names only attributes, views, and Malli's scalars and collections. No var, no
   schema built by a call.
5. A shape with no identity (`Address`) is a value shape of the vocabulary. It passes under the
   attribute embedding it (`:customer/address`).
6. Two uses may each have a `summary`: `:checkout/summary` and `:billing/summary` are two names.

## Not settled

- `shop.domain` installs the Malli registry itself. Every project would copy those lines.
- A thing that flows but is no part of the domain (a clock, a connection) has no spelling here
  yet. `shop.store` takes scalars.
- `shop.domain` is not in the design: its two functions are that plumbing.
