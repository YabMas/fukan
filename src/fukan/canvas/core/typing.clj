(ns fukan.canvas.core.typing
  "The type-dialect plug-point: a slot for a project's TYPE dialect. Kernel-owned —
   the third plug-point alongside the extractor and the render multimethod — because
   the kernel itself consumes it: a refined slot target (a data-literal type form,
   e.g. `[:enum …]`) compiles to a law that checks values through `value-valid?`.

   Fukan ships only this mechanic — the registry is language-NEUTRAL and the kernel
   never interprets a type form. A dialect is a map of bridge fns + its value-structure
   tag at the edges:
     :render      (fn [db eid] → code-form)            type subgraph → code
     :parse       (fn [form]   → entity-maps)          code-form → datoms (later)
     :adheres?    (fn [model-form code-form] → bool)   model type ↔ realized code type
     :valid?      (fn [type-form value] → bool)        scalar value ⊨ refined slot type
     :reflect-tag <value-structure tag>                the dialect's `^:value` type tag

   The dialect contributes only its `:reflect-tag` (data, not a fn) for reflection: the
   KERNEL does the building (`reflect-type` runs the generic value machinery on that tag,
   whose reader interprets the form), so a dialect bridge NEVER reaches back into kernel
   internals — the deliberate SPI, not piecemeal exposure. Consumers that must recognize a
   reflected type value read `dialect-type-tag` rather than hard-coding the dialect's tag.

   Registration MERGES per key, so capabilities compose across registrars: a grammar
   (e.g. `canvas.vocab.type`) contributes `:valid?` + `:reflect-tag` when it loads — opting a
   model into the dialect wires the checking and reflection — while a composition root
   contributes `:render`/`:adheres?`. Re-registering a key replaces that key."
  (:require [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.substrate :as sub]))

(defonce ^:private dialect (atom nil))

(defn register-type-dialect!
  "Merge `d` (a bridge-fn map) into the registered dialect. Later registrations of
   the same key replace it; distinct keys compose."
  [d]
  (swap! dialect merge d)
  nil)

(defn ^:test-support registered-dialect
  "The currently registered dialect map (or nil) — for save/restore in tests."
  []
  @dialect)

(defn ^:test-support clear-type-dialect!
  "Drop the registered dialect entirely (tests / re-composition)."
  []
  (reset! dialect nil)
  nil)

(defn render-type
  "Render the type at `eid` in `db` to a code-form via the dialect, or nil."
  [db eid]
  (when-let [f (:render @dialect)] (f db eid)))

(defn parse-type
  "Parse a code-form type into entity-maps via the dialect, or nil."
  [form]
  (when-let [f (:parse @dialect)] (f form)))

(defn type-adheres?
  "Check a modelled type code-form against a realized code type-form via the dialect, or nil."
  [model-form code-form]
  (when-let [f (:adheres? @dialect)] (f model-form code-form)))

(defn dialect-type-tag
  "The registered dialect's value-structure tag (its `:reflect-tag`) — the `:structure/of` of a
   reflected type node, or nil when no dialect is registered. Consumers that must distinguish a
   reflected type value (render it via `render-type`) from a named structure read this rather than
   hard-coding the dialect's tag."
  []
  (:reflect-tag @dialect))

(defn reflect-type
  "Reflect a type `form` (a scalar keyword or a refined vector) into its content-deduped subgraph, or
   nil when no dialect is registered. The dialect contributes only its value-structure tag
   (`:reflect-tag`); the KERNEL builds — `value-literal->iv` constructs the value IV for that tag
   (the tag's own reader interprets the form), `value-content-key` identifies it, the assembler emits
   it. So reflection runs entirely on kernel machinery and the dialect never reaches in. The returned
   map carries `:id` (the root content key) plus `:nodes`/`:rels`."
  [form]
  (when-let [tag (:reflect-tag @dialect)]
    (let [iv  (s/value-literal->iv tag form)
          key (sub/value-content-key iv)]
      (assoc (a/emit-instances [[key iv]]) :id key))))

(defn ^:export value-valid?
  "Does scalar `value` satisfy the dialect type `form`? The refined-slot law bridge —
   generated slot laws reference this fn by symbol, late-bound through the registry.
   Unlike the other bridges this THROWS when no `:valid?` is registered: a law that
   silently passes unverified is worse than a loud missing-dialect error."
  [form value]
  (if-let [f (:valid? @dialect)]
    (f form value)
    (throw (ex-info (str "a refined slot target (" (pr-str form) ") needs a registered "
                         "type dialect with :valid? — require the type grammar (e.g. "
                         "canvas.vocab.type) or register one at the composition root")
                    {:form form :value value}))))
