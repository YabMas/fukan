(ns fukan.canvas.core.typing
  "The type-dialect plug-point: a slot for a project's TYPE dialect. Kernel-owned —
   the third plug-point alongside the extractor and the render multimethod — because
   the kernel itself consumes it: a refined slot target (a data-literal type form,
   e.g. `[:enum …]`) compiles to a law that checks values through `value-valid?`.

   Fukan ships only this mechanic — the registry is language-NEUTRAL and the kernel
   never interprets a type form. A dialect is a map of bridge fns at the edges:
     :render   (fn [db eid] → code-form)            type subgraph → code
     :parse    (fn [form]   → entity-maps)          code-form → datoms (later)
     :adheres? (fn [model-form code-form] → bool)   model type ↔ realized code type
     :valid?   (fn [type-form value] → bool)        scalar value ⊨ refined slot type

   Registration MERGES per key, so capabilities compose across registrars: a grammar
   (e.g. `lib.type.malli`) contributes `:valid?` when it loads — opting a model into
   the dialect wires the checking — while a composition root contributes
   `:render`/`:adheres?`. Re-registering a key replaces that key.")

(defonce ^:private dialect (atom nil))

(defn register-type-dialect!
  "Merge `d` (a bridge-fn map) into the registered dialect. Later registrations of
   the same key replace it; distinct keys compose."
  [d]
  (swap! dialect merge d)
  nil)

(defn registered-dialect
  "The currently registered dialect map (or nil) — for save/restore in tests."
  []
  @dialect)

(defn clear-type-dialect!
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
                         "lib.type.malli) or register one at the composition root")
                    {:form form :value value}))))
