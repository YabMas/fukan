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

1. The vocabulary is one `lexicon/vocabulary` over a literal vector of shapes, installed where
   it is declared (`lexicon.install/vocabulary!`).
2. A view is declared, by name, in the namespace that takes it: `lexicon/views` over a literal
   map, handed to `lexicon.install/views!` before the namespace's first function.
3. A function is a `malli.experimental/defn`, annotated in full.
4. A signature names only attributes, views, and Malli's scalars and collections. No var, no
   schema built by a call.
5. A shape with no identity (`Address`) is a value shape of the vocabulary. It passes under the
   attribute embedding it (`:customer/address`).
6. Two uses may each have a `summary`: `:checkout/summary` and `:billing/summary` are two names.

## Several languages

A system speaks more than one language. The shop's own vocabulary (`shop.domain`, named
`:shop`) is the language of selling. How a request is handled is a domain of its own, with a
language of its own, and the shop takes it from a library shared between systems,
`reference/web` (named `:web`).

- **Ownership.** Each vocabulary owns its shapes, and a name has one owner. A shape that two
  vocabularies declare is refused, by Lexicon when the code loads and by fukan when it reads
  the source. So is a second vocabulary under a name already taken. Nothing is ever merged.
- **References.** The shop's vocabulary `:uses` web's. That lets a shop entity refer to a web
  entity by its one name: a `Login` holds `:login/session` (a `:web.session`) beside
  `:login/customer`.
- **What references cost.** A vocabulary shared between systems cannot refer to any one
  system's entity, so web has no way to say which customer a session belongs to. The shop says
  it instead, with an entity of its own (`Login`). A second name for one entity (an alias) is
  deferred until references are shown to fall short.
- **Where the languages meet.** fukan reads, from signatures alone, which namespace speaks which
  language (`lexicon/languages`) and which functions speak more than one
  (`lexicon/translators`). Here only `shop.front`'s two functions do: a request comes in, and
  what the shop understands goes out. Both are readings, not laws.
- **The library on the classpath.** fukan's check takes one `--src`. It finds web's vocabulary
  on the classpath and reads it as forms, together with the views declared in the same file.
  web's code is web's own to check, and is not extracted here.

## Not settled

- A thing that flows but is no part of the domain (a clock, a connection) has no spelling here
  yet. `shop.store` takes scalars.
