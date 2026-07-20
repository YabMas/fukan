(ns fukan.canvas.core.structure
  "The lean-kernel structure primitive — the heart of the kernel.

   `defstructure` fuses composition, authoring grammar, and constraint into one
   form, on the insight that *a slot is a relation with a law*: one slot declaration
   yields composition (a Relation), authoring grammar (an instantiation clause), and
   a datalog constraint at once. The structure substrate IS the model — no separate
   model-map, no privileged kinds.

   A structure instance is a Node tagged `:structure/of <Tag>`. Slots are declared as
   one map of `rel → type-expr`, cardinality as a quantifier (bare = one, `:?` optional,
   `:*` zero+, `:+` one+ — both ordered — `:set` unordered). A slot whose target
   is another structure reifies a Relation (`:rel/from` → `:rel/to`, `:rel/kind`,
   optional `:rel/label` from an authored `[label target]` clause, `:rel/order` for
   sequence slots) so every cross-reference stays queryable; a slot whose target is a
   scalar stores a `:val/<slot>` leaf with an auto-generated type-check law — a
   vector target (`[:enum \"a\" \"b\"]`, `[:int {:min 1}]`) is a REFINED scalar whose
   law checks values through the registered type dialect (the core stores the type
   form verbatim and never interprets it). `check`
   runs every structure's laws (slot-cardinality laws + free `law`s, recursive
   datalog rules supported) over a db, injecting the vocab-derived rules so laws read
   at domain altitude. The schema is minimal and classification-free."
  (:require [clojure.string :as str]
            [fukan.canvas.core.rules :as rules]
            ;; the node substrate this grammar sits on (the InstanceValue the macro emits,
            ;; node identity, the empty db) lives one layer down
            [fukan.canvas.core.substrate :as sub :refer [->InstanceValue]]))

;; ── value-slot classification ─────────────────────────────────────────────────
;; A slot is a VALUE slot when its target is a TYPE FORM — a scalar keyword or a
;; refined vector — owned by the type dialect. Classification is purely SYNTACTIC,
;; set at parse time (`:type-form?`). The kernel knows no specific scalar types:
;; every value-slot check is routed through the dialect's `value-valid?`.

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  scalar-slot?
  "True when a slot is a VALUE slot (its target is a type form — a scalar keyword or a
   refined vector — owned by the type dialect), as opposed to a structure-ref slot.
   Purely syntactic: set at parse time (`:type-form?`); the kernel knows no specific types."
  [slot]
  (boolean (:type-form? slot)))


;; ── structure registry (vocabulary as data: slots + laws, no family/payload) ──

(defonce ^:private structures (atom {}))

(defn ^:export register-structure!
  "Register `sdef` under its tag. A structure's tag is QUALIFIED (identity = defining ns + name),
   so structures sharing a short name coexist. A RELATION element's tag is UNQUALIFIED — its
   datalog rule name is global — so its NAME is signature identity: re-declaring the same relation
   tag from a DIFFERENT namespace would silently replace the first declaration (the registry keys
   by tag) and every law over the name would read only the survivor. That collision THROWS here,
   at declaration — the registry is the only place the first declaration still exists to compare
   against. Same-namespace re-registration (a REPL reload) replaces as before."
  [sdef]
  (when-not (namespace (:tag sdef))
    (when-let [prior (get @structures (:tag sdef))]
      (when (not= (:ns prior) (:ns sdef))
        (throw (ex-info (str "relation " (:tag sdef) " is already declared by " (:ns prior)
                             " — a relation's rule name is global, so a re-declaration by "
                             (:ns sdef) " would silently shadow it. Rename one of the two.")
                        {:tag (:tag sdef) :declared-by (:ns prior) :redeclared-by (:ns sdef)})))))
  (swap! structures assoc (:tag sdef) sdef)
  (:tag sdef))
(defn ^{:malli/schema [:=> [:cat] [:vector :any]]}
  all-structures [] (vals @structures))
(defn ^{:malli/schema [:=> [:cat :keyword] :any]}
  structure-by-tag [tag] (get @structures tag))

;; ── instantiation (the interpreter: instance → Node + reified slot Relations) ─

(declare correspondence-of syntax-for)
(defn- slot-for
  "The slot descriptor for `rel` on `sdef`'s structure. An extracted instance's fact-side slots
   (`:calls`/`:private`/…) are now its OWN defstructure's slots — the codomain (`Fn`/`Ns`) is a real
   structure — so this reads `sdef` directly; no external graft to fold in."
  [sdef rel]
  (first (filter #(= rel (:rel %)) (:slots sdef))))

(defn- sdef-syntax
  "The authoring-syntax hook for `sdef` — its inline `(syntax …)` OR one registered externally against
   its tag (`register-syntax!`). Authoring sugar is MACHINERY, so a concept may keep its identity
   defstructure clean and register the hook from outside."
  [sdef] (or (:syntax sdef) (syntax-for (:tag sdef))))


;; ── value-authoring: instance-form / value-form ──────────────────────────────

(defn- unquote-lit [v] (if (and (seq? v) (= 'quote (first v))) (second v) v))

;; ── authoring clause composition ─────────────────────────────────────────────

(defn- path-segment
  "Parse a path segment keyword into `[rule quantifier]`, where quantifier is one
   of `:one`, `:one+`, or `:zero+`. The compact authoring surface mirrors regular
   path notation: `:calls`, `:calls+`, `:calls*`."
  [seg]
  (when-not (or (keyword? seg) (symbol? seg))
    (throw (ex-info (str "path segment must be a keyword or symbol: " (pr-str seg)) {:segment seg})))
  (let [n (name seg)]
    (cond
      (str/ends-with? n "*") [(symbol (subs n 0 (dec (count n)))) :zero+]
      (str/ends-with? n "+") [(symbol (subs n 0 (dec (count n)))) :one+]
      :else                  [(symbol n) :one])))

(defn- dvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- and-disjunct [clauses]
  (case (count clauses)
    0 (throw (ex-info "path expansion produced an empty disjunct" {}))
    1 (first clauses)
    (apply list 'and clauses)))

(declare path-clauses*)

(defn- path-star-clause [from rule rest-steps to fresh]
  (let [mid (fresh)
        join-vars (vec (distinct (filter dvar? [from to])))
        zero (path-clauses* from rest-steps to fresh)
        plus (cons (list (symbol (str (name rule) "+")) from mid)
                   (path-clauses* mid rest-steps to fresh))]
    (list 'or-join join-vars (and-disjunct zero) (and-disjunct plus))))

(defn- path-clauses*
  "Compile a path expression into datalog clauses. `*` means zero or more hops,
   so `[:calls* :performs]` expands to direct `:performs` OR `calls+` followed by
   `:performs`."
  [from steps to fresh]
  (if (empty? steps)
    [(list '= from to)]
    (let [[rule q] (path-segment (first steps))
          more (next steps)
          final? (nil? more)
          target (if final? to (fresh))]
      (case q
        :one
        (cons (list rule from target)
              (when-not final? (path-clauses* target more to fresh)))

        :one+
        (cons (list (symbol (str (name rule) "+")) from target)
              (when-not final? (path-clauses* target more to fresh)))

        :zero+
        [(path-star-clause from rule more to fresh)]))))

(defn ^:export expand-clauses
  "Expand authoring-layer composition clauses into ordinary datalog clauses.

   Supported forms:
     `(path ?from [:r :s* :t+] ?to)` — relational composition over generated
     relation rules (`r`) and closure rules (`s+`/`t+`). `*` is zero-or-more,
     so it includes the zero-hop case; `+` is one-or-more.

   Expansion is top-level only, matching the existing `(via …)` behavior."
  [clauses]
  (vec
   (apply concat
          (map-indexed
           (fn [i clause]
             (if (and (seq? clause) (= 'path (first clause)))
               (let [[_ from steps to] clause]
                 (when-not (and (= 4 (count clause)) (vector? steps))
                   (throw (ex-info (str "path clause must be (path ?from [segments...] ?to): "
                                        (pr-str clause))
                                   {:clause clause})))
                 (let [counter (atom 0)
                       fresh (fn []
                               (symbol (str "?_path" i "_" (swap! counter inc))))]
                   (path-clauses* from steps to fresh)))
               [clause]))
           clauses))))

(defn- expr->path-segments
  "Lower a relation-algebra expression `E` (the correspondence relation-map DSL) to the flat
   path-segment vector the `path` builtin consumes: an atom `:r` → `:r`, `[:+ :r]` → `:r+`,
   `[:* :r]` → `:r*`, `[:cat E …]` concatenates its parts' segments. Closure over a COMPOUND, `:?`,
   and `:alt` need the derived-rule compiler (arrives with the roll-up in the next step) and throw
   here — a clear expansion-time error, not a mystery at check.

   `E` is regular relations (Kleene algebra over fact relations), spelled as the regex AST malli
   itself uses; the flat path segments are the `:r`/`:r+`/`:r*` suffix notation `path` already reads."
  [expr]
  (cond
    (keyword? expr) [expr]
    (and (vector? expr) (seq expr))
    (let [[op & args] expr]
      (case op
        :cat    (vec (mapcat expr->path-segments args))
        (:+ :*) (let [[inner] args]
                  (when-not (keyword? inner)
                    (throw (ex-info (str "relation-map: " op " over a compound expression needs the "
                                         "derived-rule compiler (not yet built): " (pr-str expr)) {:expr expr})))
                  [(keyword (str (name inner) (name op)))])   ; :+/:* → the closure suffix the path builtin reads
        (throw (ex-info (str "relation-map: unsupported expression operator " op " in " (pr-str expr)) {:expr expr}))))
    :else (throw (ex-info (str "relation-map: unreadable expression " (pr-str expr)) {:expr expr}))))

(defn- path-lowerable?
  "True when `E` lowers to flat path segments (atom, `:cat`, `:+`/`:*` over atoms) — so it inlines
   through the `path` builtin, safe wherever the law binds its endpoints. Compound closures (the
   roll-up) and `:?`/`:alt` need a derived rule instead."
  [expr]
  (cond
    (keyword? expr) true
    (and (vector? expr) (seq expr))
    (case (first expr)
      :cat    (every? path-lowerable? (rest expr))
      (:+ :*) (keyword? (second expr))
      false)
    :else false))

(defn- reach-clause
  "A single datalog clause binding `from`→`to` over the fact expression `E` — a regular-relation term
   (an atom, `:cat`, `:+`/`:*` over atoms), lowered through the `path` builtin. An `E` that needs its
   OWN recursion (the public call graph) is a NAMED fact relation — a `defrelation` — referenced here as
   an atom, so the complex definition lives with the relation, not inline in the correspondence."
  [expr from to]
  (when-not (path-lowerable? expr)
    (throw (ex-info (str "relation-map: expression " (pr-str expr) " is not a regular-relation path — "
                         "name it a `defrelation` and reference it as an atom") {:expr expr})))
  (first (expand-clauses [(list 'path from (expr->path-segments expr) to)])))

(defn- ref-arg->form
  "Code for one relation-slot target: a symbol → (var sym); an inline (Tag ...) form
   → left to evaluate (it yields an InstanceValue)."
  [arg]
  (if (symbol? arg) (list 'var arg) arg))

(defn- reader-literal?
  "True when `arg` is a data literal that a reader-slot should expand (rather than
   var-capture). A reader takes a native literal — a symbol (Shape `Foo`), a keyword
   (Effect `:io`), a number (Wrapped `5`), a string, a map (Shape record), or a vector
   (Shape `[X]`). The only non-literals are an inline `(Tag …)` constructor seq and a
   2-element symbol-headed `[label target]` form (parsed upstream as a labelled target)."
  [arg]
  (and (not (seq? arg))
       (not (and (vector? arg) (= 2 (count arg)) (symbol? (first arg))))))

(declare value-form)

(defn- reader-arg->form
  "Like `ref-arg->form` but for a reader-slot: a data literal (symbol/vector/map,
   NOT an inline `(Tag …)` seq) is expanded via the reader at macroexpansion time
   and the resulting clauses are built into an inline value-form; an inline seq
   form is left as-is (normal inline construction)."
  [target-tag reader-fn arg]
  (if (and (reader-literal? arg) (not (seq? arg)))
    ;; data literal → expand via reader → build value-form at macroexpansion time
    (value-form target-tag (reader-fn arg))
    ;; inline (Tag …) form → leave to normal evaluation
    (ref-arg->form arg)))

(defn- rel-map-form
  "Emits a form for a single relation-clause map.  `:targets` is a vector of
   *code forms* (e.g. `(var User)`) so they evaluate to vars / InstanceValues
   when the surrounding `->InstanceValue` call is evaluated.
   `labels` is a vector parallel to `:targets` (nil entries for unlabelled
   targets) — a `:labels` key is added only when some target is labelled, so a
   clause like `(takes [x Int] [y Str])` carries a per-target label."
  ([rk card targets]
   `{:rk ~rk :card ~card :targets [~@targets]})
  ([rk card targets labels]
   (if (some some? labels)
     `{:rk ~rk :card ~card :targets [~@targets] :labels [~@labels]}
     `{:rk ~rk :card ~card :targets [~@targets]})))

(defn- parse-clause-arg-forms
  "Given a clause's raw args and optionally the target structure's sdef (when it
   has a `:reader`), return `[labels target-forms]` where `target-forms` is a seq
   of code forms to splice into `:targets` and `labels` is a parallel vector (nil
   per unlabelled target). Each arg is one element — multi-slots are varargs, and
   for sequence slots the authoring order IS the order: a 2-element, symbol-headed
   vector `[label t]` contributes a labelled target; a bare arg an unlabelled one
   (so `(takes [x Int] [y Str])` carries per-target labels). (Malli forms are
   keyword-headed and bare Kind refs are symbols, so the label shape never misfires
   on a Schema `:of` element or a grammar `:rhs` symbol.)

   When `target-sdef` has a `:reader`, data literals are expanded via the reader
   (§2.1 reader-slot exception). A `[label literal]` form for a reader-slot
   extracts the label and reader-expands the target part."
  ([args] (parse-clause-arg-forms args nil))
  ([args target-sdef]
   (let [arg->form  (if (and target-sdef (:reader target-sdef))
                      (partial reader-arg->form (:tag target-sdef) (:reader target-sdef))
                      ref-arg->form)
         parse-elem (fn [a]
                      (if (and (vector? a) (= 2 (count a)) (symbol? (first a)))
                        {:label (str (first a)) :target (arg->form (second a))}
                        {:label nil :target (arg->form a)}))
         parsed     (mapv parse-elem args)]
     [(mapv :label parsed) (mapv :target parsed)])))

(def ^:private reserved-annotation-keys
  "Kernel-level per-instance ANNOTATION keys — free-text notes an author may attach to ANY instance,
   not vocab slots. Stored as `:val/<key>` leaves (like a scalar slot's value, but with no declared
   slot and no law); consumed by projections, never by laws. `:guidance` is implementer-directed
   design intent — the read dual of the docstring (doc = prose-for-the-reader; guidance =
   prose-for-the-implementer). Available on every structure without being defined in any of them."
  #{:guidance})

(defn- reserved-annotation-key? [k] (contains? reserved-annotation-keys k))

(defn- map-entry->clause
  "One slots-map entry `slot → value` → the internal clause form. The encoding is
   schema-driven — the slot's declared quantifier/payload disambiguates the value:

     :one/:optional    bare value         (k v)         a `[label target]` pair stays one element
     :many/:some/:set  vector of targets  (k v1 v2 …)   the bracket mirrors the quantifier
     payload slot      [value payload]    (k value payload)

   A reserved annotation key (`:guidance`) needs no slot — it emits a bare leaf clause `(k v)`."
  [tag sdef [k v]]
  (let [slot (slot-for sdef k)
        head (symbol (clojure.core/name k))]
    (when (and (not slot) (not (reserved-annotation-key? k)))
      (throw (ex-info (str (clojure.core/name tag) ": `" (clojure.core/name k) "` is not a slot")
                      {:tag tag :rel k})))
    (cond
      (reserved-annotation-key? k) (list head v)

      (and (scalar-slot? slot) (:payload slot) (vector? v))
      (do (when-not (= 2 (count v))
            (throw (ex-info (str (clojure.core/name tag) "." (clojure.core/name k)
                                 ": a payload slot takes [value payload] — got " (pr-str v))
                            {:tag tag :rel k})))
          (list head (first v) (second v)))

      (scalar-slot? slot) (list head v)

      (#{:many :some :set} (:card slot))
      (do (when-not (or (vector? v) (set? v))
            (throw (ex-info (str (clojure.core/name tag) "." (clojure.core/name k)
                                 ": a plural slot takes a vector of targets — got " (pr-str v))
                            {:tag tag :rel k})))
          (cons head (seq v)))

      :else (list head v))))

(defn- map->clauses
  "The {slot → value} authoring map → the internal clause IR (which readers and
   nesting also feed). The map is the author surface; clauses are the mechanism."
  [tag sdef m]
  (mapv #(map-entry->clause tag sdef %) m))

(defn- apply-syntax
  "Run the structure's instance-level `(syntax f)` hook over the authored slots
   map — f : map → map (e.g. Operation rewrites :signature into :in/:out). The
   transform lives in the vocab; core just invokes it."
  [sdef m]
  (if-let [syn (sdef-syntax sdef)] (syn m) m))

(defn- syntax-input
  "The non-nested instance body handed to a `(syntax f)` hook. With no syntax hook,
   the only legal body is one slots map. With a hook, one body form keeps the old
   behavior; multiple forms are passed as a vector so the vocab can own a compact
   domain surface such as `(Operation f signature opts)`."
  [sdef body]
  (cond
    (empty? body) {}
    (and (sdef-syntax sdef) (next body)) (vec body)
    :else (first body)))

(defn- build-instance-form
  "Shared clause-walker behind `instance-form`, `value-form` and `expand-instance`.
   Builds the `->InstanceValue` call with `name-expr` (a string form or nil-literal),
   `doc` (a string or nil) and `value?-expr` (true/false literal). Validates slot
   names; separates scalar clauses from relation clauses; emits target-capture
   forms via `ref-arg->form`.

   §2.1 reader-slot exception: when a slot's target structure declares a `:reader`,
   data literals (symbol/vector/map, not inline `(Tag …)` seqs) in that slot are
   expanded via the reader at macroexpansion time and inlined as value-forms."
  [tag name-expr doc value?-expr clauses]
  (let [sdef    (structure-by-tag tag)
        _       (when-not sdef
                  (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
        scalar? (fn [c] (or (reserved-annotation-key? (keyword (first c)))
                            (let [s (slot-for sdef (keyword (first c)))] (and s (scalar-slot? s)))))
        scalars (into {} (for [c clauses :when (scalar? c)
                               :let [slot (slot-for sdef (keyword (first c)))]
                               pair (cond-> [[(keyword "val" (clojure.core/name (first c))) (second c)]]
                                      (and (:payload slot) (>= (count c) 3))
                                      ;; A payload carries a companion CODE-FORM (a datalog
                                      ;; query, a predicate `(fn …)`) — stored as DATA, not
                                      ;; evaluated. Strip any authoring quote then re-quote so
                                      ;; the leaf holds the form verbatim (symbols like ?n
                                      ;; survive instead of resolving at runtime).
                                      (conj [(keyword "val" (clojure.core/name (:payload slot)))
                                             (list 'quote (unquote-lit (nth c 2)))]))]
                           pair))
        rels    (mapv (fn [c]
                        (let [rk         (keyword (first c))
                              slot       (slot-for sdef rk)
                              target-sdef (when slot (structure-by-tag (:target slot)))]
                          (when-not slot
                            (throw (ex-info (str (clojure.core/name tag) ": `"
                                                 (clojure.core/name rk) "` is not a slot")
                                            {:tag tag :rel rk})))
                          (let [[labels target-forms] (parse-clause-arg-forms (rest c) target-sdef)]
                            (rel-map-form rk (:card slot) target-forms labels))))
                      (remove scalar? clauses))]
    `(->InstanceValue ~tag ~name-expr ~doc ~scalars ~rels ~value?-expr)))

(defn ^:export instance-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for an EXPRESSION-position
   entity instance — `(Tag \"doc\"? {slot → value}?)`, mirroring defstructure's
   docstring + one-map shape. The name is always nil: the assembler derives
   `:entity/name` from the binding var's simple name; a named top-level instance
   authors as the def-emitting `(Tag sym …)` form (see `expand-instance`)."
  [tag args]
  (let [sdef (structure-by-tag tag)
        _    (when-not sdef
               (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
        doc  (when (string? (first args)) (first args))
        body (if doc (rest args) args)
        one  (first body)]
    ;; a structure that declares a (syntax …) hook may take a single POSITIONAL body
    ;; (a non-map arg-tail) — the hook normalizes it to the slots map; without a hook
    ;; the body must still be empty or a single slots map.
    (when-not (or (empty? body)
                  (and (empty? (rest body)) (or (map? one) (sdef-syntax sdef)))
                  (and (sdef-syntax sdef) (seq body)))
      (throw (ex-info (str (clojure.core/name tag) ": an instance is `("
                           (clojure.core/name tag) " \"doc\"? {slot → value}?)` — got "
                           (pr-str (vec body)))
                      {:tag tag :body (vec body)})))
    (build-instance-form tag nil doc false
                         (map->clauses tag sdef (apply-syntax sdef (syntax-input sdef body))))))

(defn ^:export value-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for a ^:value instance —
   anonymous (name=nil) and content-identified (value?=true). The author surface is
   `(Tag {slot → value})`; a clause-vector body is the internal IR readers emit
   (`reader-arg->form` calls this with the reader's expansion)."
  [tag body]
  (let [sdef (structure-by-tag tag)]
    (when-not sdef
      (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
    (cond
      (and (= 1 (count body)) (map? (first body)))
      (build-instance-form tag nil nil true
                           (map->clauses tag sdef (apply-syntax sdef (first body))))

      (and (= 1 (count body)) (:reader sdef) (reader-literal? (first body)))
      (build-instance-form tag nil nil true ((:reader sdef) (first body)))

      :else
      (build-instance-form tag nil nil true body))))

;; ── the named-instance surface: def-emitting, defstructure's mirror ──────────
;; `(Tag sym "doc"? {slot → value}? nested…)` is a TOP-LEVEL def-emitting form — the
;; instance surface mirrors defstructure position-for-position (name symbol, docstring,
;; one map; nested member instances trail where defstructure's laws do). The symbol is
;; the var AND the entity name; nested `(Tag sym …)` instances become sibling `def`s
;; (so cross-refs stay VAR-refs) and route by target-type into the container's slots.

(defn- resolve-struct-tag
  "Resolve a slot/nesting target SYMBOL to its ns-qualified structure tag — the structure's
   identity is its defining namespace + name (a qualified keyword), mirroring its constructor var,
   so co-loaded structures that share a short name no longer collide in one global registry.
   `Any` is the bare wildcard. A symbol that does not resolve yet — a self-reference (`Step` →
   `Step`) or a same-ns forward ref — is assumed to live in the current ns."
  [sym]
  (cond
    (= 'Any sym) :Any
    :else (if-let [v (resolve sym)]
            (keyword (str (ns-name (:ns (meta v)))) (name (:name (meta v))))
            (keyword (str (ns-name *ns*)) (name sym)))))

(defn- nested-instance?
  "A body form `(Tag sym …)` where Tag is a registered structure and sym a symbol — a nested
   named instance to lift (vs a slot/law clause, or an inline ^:value form)."
  [f]
  (and (seq? f) (symbol? (first f)) (>= (count f) 2) (symbol? (second f))
       (structure-by-tag (resolve-struct-tag (first f)))))

(defn- route-slot
  "Which slot a nested instance of `kid-tag` routes to in `sdef`: the slot whose target IS that
   tag (the role slot — an Operation → :exposes, a Kind → :owns), unless `private?`, then the
   `Any`-targeting fallback (the internal :child slot)."
  [sdef kid-tag private?]
  (or (when-not private?
        (some #(when (= (:target %) kid-tag) (:rel %)) (:slots sdef)))
      (some #(when (= (:target %) :Any) (:rel %)) (:slots sdef))))

(declare expand-instance)

(defn ^:export expand-instance
  "Def-emitting + nesting expansion of `(sym \"doc\"? {slot → value}? nested…)` for
   structure `tag` — the named-instance authoring surface. Returns {:defs [forms] :sym :tag}:
   nested named instances are lifted to sibling `def`s (cross-refs stay var-refs) and routed
   by target-type into the container's slots; this instance's `def` is last. The leading
   symbol is the name AND the var; `^{:name \"…\"}` metadata on it overrides the entity
   name (the rare case: a name the var can't carry, or same-named instances across cases)."
  [tag args]
  (let [sym   (first args)
        more  (rest args)
        doc   (when (string? (first more)) (first more))
        body  (if doc (rest more) more)
        sdef  (structure-by-tag tag)
        _     (when-not sdef
                (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
        nests (filter nested-instance? body)
        cls   (remove nested-instance? body)
        one   (first cls)
        _     (when-not (or (empty? cls)
                            (and (empty? (rest cls)) (or (map? one) (sdef-syntax sdef)))
                            (and (sdef-syntax sdef) (seq cls)))
                (throw (ex-info (str (clojure.core/name tag) " " sym ": an instance is `("
                                     (clojure.core/name tag)
                                     " name \"doc\"? {slot → value}? nested…)` — got "
                                     (pr-str (vec cls)))
                                {:tag tag :sym sym :body (vec cls)})))
        m     (apply-syntax sdef (syntax-input sdef cls))
        kids  (mapv (fn [nf] (assoc (expand-instance (resolve-struct-tag (first nf)) (rest nf))
                                    :private? (boolean (:private (meta (second nf)))))) nests)
        routed (->> kids
                    (group-by #(route-slot sdef (:tag %) (:private? %)))
                    (map (fn [[rel ks]] (cons (symbol (name rel)) (map :sym ks)))))
        clauses (concat (map->clauses tag sdef m) routed)
        value   (build-instance-form tag (or (:name (meta sym)) (name sym)) doc false clauses)]
    ;; forward-declare the nested syms so they may cross-reference each other (and the parent
    ;; reference them) in any authoring order — `(var X)` captures the var; its value is read
    ;; later, at assemble time, once every def has run.
    {:defs (concat (when (seq kids) [(cons 'declare (map :sym kids))])
                   (mapcat :defs kids)
                   [(list 'def sym value)])
     :sym sym :tag tag}))

(defn ^{:malli/schema [:=> [:cat :keyword :any] :any]}
  value-literal->iv
  "Build a ^:value InstanceValue for value-structure `tag` from a data `literal`,
   recursing into relation targets via THEIR readers. The ONE value-construction
   path — used by reflection (a slot's type form → its Schema subgraph), so content
   keys match by construction."
  [tag literal]
  (let [sdef    (structure-by-tag tag)
        clauses ((:reader sdef) literal)
        slot-of (fn [k] (some #(when (= k (:rel %)) %) (:slots sdef)))]
    (reduce
     (fn [iv [head & args]]
       (let [sl (slot-of (keyword head))]
         (cond
           (nil? sl)
           (throw (ex-info (str "reader for " tag " emitted unknown clause " head) {:literal literal}))
           (scalar-slot? sl)
           (assoc-in iv [:scalars (keyword "val" (name head))] (first args))
           :else
           ;; relation slot: each arg is a plain target form OR a `[label target]` pair — a vector
           ;; with a SYMBOL head (e.g. an arrow Schema's `[param-name type]` :in entry). Mirror the
           ;; macro's labelled-target handling (parse-clause-arg-forms) so reflection matches
           ;; authoring; malli forms are keyword-headed and bare refs are keywords, so a symbol-headed
           ;; vector unambiguously denotes a label.
           (let [ttag   (:target sl)
                 parsed (mapv (fn [a]
                                (if (and (vector? a) (symbol? (first a)))
                                  {:label (str (first a)) :target (second a)}
                                  {:label nil :target a}))
                              args)]
             (when-not (:reader (structure-by-tag ttag))
               (throw (ex-info (str "cannot reify type form " (pr-str literal) " — slot target "
                                    ttag " has no reader")
                               {:tag tag :literal literal})))
             (update iv :clauses conj
                     (cond-> {:rk (:rel sl) :card (:card sl)
                              :targets (mapv #(value-literal->iv ttag (:target %)) parsed)}
                       (some :label parsed) (assoc :labels (mapv :label parsed))))))))
     (->InstanceValue tag nil nil {} [] true)
     clauses)))

;; ── defstructure (the one form) ──────────────────────────────────────────────

(def ^:private quantifiers
  "Surface quantifier → slot cardinality. `:many` (`:*`) and `:some` (`:+`) are
   SEQUENCES — authoring order is recorded as `:rel/order` and enters value
   identity; `:set` is unordered — order is excluded from identity and duplicate
   targets collapse. The unmarked case is `:one`."
  {:? :optional, :* :many, :+ :some, :set :set})

(defn- parse-slot-entry
  "One slots-map entry `rel → type-expr` → {:rel :card :target & opts}:

     :reads Model                      one (the default — a bare target)
     :doc   [:? :string]               optional
     :child [:* Node]                  zero or more, ordered
     :item  [:+ Item]                  one or more, ordered
     :field [:set Field]               zero or more, unordered identity
     :mode  [:enum \"a\" \"b\"]            a refined scalar, cardinality one

   A quantifier takes malli's props position for slot options: `[:? {:payload :q} :string]`;
   for the default card, lead with the props map: `[{:payload :q} :string]`.
   The target form: a SYMBOL resolves to a structure tag (a ref-slot; `Any` is the
   wildcard). A KEYWORD or VECTOR is a TYPE FORM (a value-slot): stored verbatim and
   never interpreted by the kernel — the generated law checks values through the
   registered type dialect (`fukan.canvas.core.typing/value-valid?`)."
  [rel v]
  (let [[card props form] (cond
                            (and (vector? v) (contains? quantifiers (first v)))
                            (let [props (when (map? (second v)) (second v))]
                              [(quantifiers (first v)) props (if props (nth v 2 nil) (second v))])
                            (and (vector? v) (map? (first v)))
                            [:one (first v) (second v)]
                            :else [:one nil v])
        type-form? (or (keyword? form) (vector? form))   ; symbol → structure-ref; else → a type form
        target (cond
                 (symbol? form)  (resolve-struct-tag form)
                 (vector? form)  form
                 (keyword? form) (keyword (name form))
                 :else (throw (ex-info (str "slot " rel ": unreadable type expression " (pr-str v))
                                       {:rel rel :form v})))]
    (merge {:rel rel :card card :target target :type-form? type-form?} props)))

;; ── law combinators: the recurring law shapes, datalog-correct by construction ─
;; A combinator names a law SHAPE at domain altitude and expands to the datalog —
;; the negation is a direct `not-join`, which Cozo's stratified negation evaluates
;; correctly (the datascript wholly-empty-relation gotcha that once forced hand-rolled
;; negation rules is gone). The authored form is kept on the law as :src (the
;; print-dual renders it back).

(defn- qualifier-clauses
  "A combinator's `:when` subject-filter → positive where-clauses on `var`. A scalar MAP
   `{k v}` is sugar for `:val/k`-equality (`[var :val/k v]` per entry); a raw datalog
   clause-VECTOR is spliced verbatim (it uses `var` as the subject, e.g.
   `'[(design ?x) [?xr :rel/kind :exposes] [?xr :rel/to ?x]]`) — the same datalog the
   correspondence demands' `:when`/`:require` accept. nil → none."
  [var when]
  (cond
    (nil? when) []
    (map? when)  (mapv (fn [[k v]] [var (keyword "val" (clojure.core/name k)) v]) when)
    :else        (vec (unquote-lit when))))

(defn- exemption-clauses
  "A combinator's `:unless` exemption → NEGATED where-clauses on `var`. A scalar MAP `{k v}`
   is sugar for `(not [var :val/k v])` per entry; a raw datalog clause-VECTOR has each clause
   negated (`(not c)`), like the `covered` demand's `:unless`. nil → none."
  [var unless]
  (cond
    (nil? unless) []
    (map? unless)  (mapv (fn [[k v]] (list 'not [var (keyword "val" (clojure.core/name k)) v])) unless)
    :else          (mapv (fn [c] (list 'not c)) (unquote-lit unless))))

(defn- combinator-law
  "Expand `(law \"desc\" (combinator …))` into a parsed law map:

     (has R :when Q? :unless E?)  every instance (satisfying :when, not :unless) has ≥1 outgoing R
     (has-any R1 R2 …)           … has at least one of the Rs
     (matched-by R :from S? :when Q? :unless E? :scope T?)
                                 every instance is the TARGET of some R (from an S)
     (target R {k v})            every R-target satisfies the value conditions
     (at-most-one R)             at most one incoming R (a unique owner/matcher)

   :when (Q) filters the law's subjects, :unless (E) exempts them — each a scalar map
   `{k v}` (sugar for `:val/k`-equality / its negation) OR a raw datalog clause-vector
   (spliced positive / negated, using `?x` as the subject), the same datalog the
   correspondence demands accept; :from constrains the matching counterpart's structure;
   :scope (a structure symbol) hosts the law about ANOTHER structure's instances (default:
   self-scoped to the owner)."
  [desc form]
  (let [[op & args] form
        kvs    (fn [xs] (apply hash-map xs))
        merged (fn [scope m] (merge {:desc desc :src form
                                     :scope (when scope (resolve-struct-tag scope))} m))]
    (case op
      has
      (let [[rel & opts] args
            {whenm :when unlessm :unless scope :scope} (kvs opts)]
        (merged scope
                {:offenders '[?x]
                 :where (conj (into (qualifier-clauses '?x whenm) (exemption-clauses '?x unlessm))
                              (list 'not-join '[?x] ['?r :rel/from '?x] ['?r :rel/kind rel]))}))
      has-any
      (merged nil
              {:offenders '[?x]
               ;; ?x has NONE of the rels = no outgoing edge for each, conjoined
               :where (mapv (fn [rel] (list 'not-join '[?x] ['?r :rel/from '?x] ['?r :rel/kind rel]))
                            args)})
      matched-by
      (let [[rel & opts] args
            {whenm :when unlessm :unless scope :scope from :from} (kvs opts)]
        (merged scope
                {:offenders '[?x]
                 :where (conj (into (qualifier-clauses '?x whenm) (exemption-clauses '?x unlessm))
                              (apply list 'not-join '[?x]
                                     (concat (when from [['?c :structure/of (resolve-struct-tag from)]])
                                             [['?r :rel/from '?c] ['?r :rel/kind rel] ['?r :rel/to '?x]])))}))
      target
      (let [[rel whenm] args]
        (merged nil
                {:offenders '[?x]
                 :where [['?r :rel/from '?x] ['?r :rel/kind rel] ['?r :rel/to '?t]
                         (apply list 'not-join '[?t] (qualifier-clauses '?t whenm))]}))
      at-most-one
      (let [[rel] args]
        (merged nil
                {:offenders '[?x]
                 :where [['?r1 :rel/kind rel] ['?r1 :rel/to '?x] ['?r1 :rel/from '?a]
                         ['?r2 :rel/kind rel] ['?r2 :rel/to '?x] ['?r2 :rel/from '?b]
                         '[(not= ?a ?b)]]}))
      (throw (ex-info (str "unknown law combinator " op " — expected has, has-any, "
                           "matched-by, target, or at-most-one")
                      {:form form})))))

;; ── declaration registry: the means-of-growth seam ───────────────────────────
;; A defstructure decomposes into DECLARATIONS, each emitting Terms (derived relations) and/or
;; Laws (constraints). A declaration handler is `(fn [decl sdef] → {:terms [rule…] :laws [law…]})`,
;; keyed by `:kind`; BOTH emitters (`terms-of`, `laws-of`) dispatch through the registry, so a new
;; kind of thing-you-can-say is a registered handler, not a new parser branch. The built-in handlers
;; (the existing constructs) register at load; the surface is unchanged.

(defonce ^:private declaration-handlers (atom {}))

(defn ^:export register-declaration!
  "Register a declaration handler — `kind → (fn [decl sdef] → {:terms [rule…] :laws [law…]})`.
   The generic seam (`terms-of`/`laws-of`) dispatches through these; built-ins register at load."
  [kind handler] (swap! declaration-handlers assoc kind handler) kind)

(defn ^:export ^{:malli/schema [:=> [:cat] [:set :any]]}
  declaration-kinds "The registered declaration kinds." [] (set (keys @declaration-handlers)))

(defn ^:export handle-declaration
  "Dispatch one declaration to its registered handler → `{:terms […] :laws […]}` (empty when the
   kind has no handler yet — Stage A registers them incrementally)."
  [decl sdef]
  (if-let [h (@declaration-handlers (:kind decl))] (h decl sdef) {:terms [] :laws []}))

(defmacro defdeclaration
  "Register a declaration handler for `kind`: `(defdeclaration :k [decl sdef] body…)` where body
   returns `{:terms [rule…] :laws [law…]}`."
  [kind [decl-sym sdef-sym] & body]
  `(register-declaration! ~kind (fn [~decl-sym ~sdef-sym] ~@body)))

;; ── external correspondence registry ──────────────────────────────────────────
;; Correspondence is an EXTENSION that hooks a concept from OUTSIDE (inverted dependency): the concept
;; knows nothing; the `(correspond Tag …)` declaration (in a correspondence module) registers its config
;; against the target's tag. `sdef->declarations` merges these in for the target sdef, so the SAME
;; handlers emit the SAME terms/laws — only the SOURCE moved from the sdef's own `:corresponds`/fact-slots
;; to here. Config: `{:bridge :demands [] :fact-slots [slot…] :rel-demands [slot-descriptor…]}`.
(defonce ^:private correspondences (atom {}))

(defn ^:export register-correspondence!
  "Register an external correspondence config against target `tag` (see the registry note). Re-registering
   a tag replaces it. Emitted by the `correspond` macro; read by `sdef->declarations`."
  [tag config] (swap! correspondences assoc tag config) tag)

(defn ^:export correspondence-of
  "The registered external correspondence config for `tag`, or nil."
  [tag] (@correspondences tag))

;; ── external authoring-syntax registry ────────────────────────────────────────
;; A `(syntax f)` hook (body map→map, applied before slot parsing) is authoring MACHINERY, not
;; identity, so a concept can register it from OUTSIDE its `defstructure` — `(register-syntax! tag f)`.
;; `sdef-syntax` reads inline-or-registered; instance construction is unchanged otherwise.
(defonce ^:private syntaxes (atom {}))

(defn ^:export register-syntax!
  "Register an authoring-syntax hook `f` (body-map → body-map) against `tag`, off the concept's
   `defstructure`. Re-registering a tag replaces it."
  [tag f] (swap! syntaxes assoc tag f) tag)

(defn ^:export syntax-for
  "The externally-registered authoring-syntax hook for `tag`, or nil."
  [tag] (@syntaxes tag))

;; ── the identity component of a correspondence morphism (derived, not authored) ─
;; A morphism's identity map = the slots BOTH theories declare under the same name AND target sort,
;; MINUS those carrying their own relation map. Those targets are a SHARED sort (a `^:value`
;; content-deduped structure — Schema/Effect — ONE node across strata), so `↦` is the identity,
;; checkable by eid. It was authored as `(agrees {:by :structural :over […]})` until 2026-07-17; now
;; it is derived from the two `defstructure`s, so the author states only the NON-identity maps.
(defn- derive-identity-demand
  "The identity component of `dtag ↦ ftag` as an `agrees` demand (nil if the two theories share no
   un-charactered sort-slot). `:by :structural` over the shared-sort slots: forward-preserving
   equality — a design instance and its twin agree iff their targets over EVERY shared slot are
   identical by eid, so a twin MISSING a slot the design declares is an offender (this is where the
   old `type-coverage` demand folds in — the `out↦out` map fails both on a differing out and on a
   missing one). `charactered` is the set of design relation names carrying their own relation map
   (the `:rel-demands`)."
  [dtag ftag dslots charactered]
  (when-let [fsdef (structure-by-tag ftag)]
    (let [fidx   (into {} (for [s (:slots fsdef)] [(:rel s) s]))
          shared (for [s dslots
                       :when (and (fidx (:rel s)) (not (scalar-slot? s))
                                  (= (:target s) (:target (fidx (:rel s))))
                                  (not (charactered (:rel s))))]
                   s)]
      (when (seq shared)
        {:demand :agrees :by :structural :derived true
         :over   (mapv :rel shared)
         :desc   (str (name dtag) ": every design instance's fact twin agrees on "
                      (str/join ", " (map (comp name :rel) shared)) " (identity map)")}))))

(defn ^:export effective-node-demands
  "The node demands of `dtag`'s correspondence: the OBJECT-MAP demands (derived from the sort map's
   inclusion — `:sub`→total, `:sup`→surjective onto the `:restrict` sub-sort, `:eq`→both) ∪ any AUTHORED
   `agrees` (the comparator escape hatch) ∪ the DERIVED identity map (tagged `:derived`). Every reader of
   the demands — the law generator (`sdef->declarations`), the seam reader (`correspondence*`), and
   grammar reflection — goes through here, so all three see the same demands. Nil when `dtag` carries no
   correspondence."
  [dtag]
  (when-let [{:keys [fact-tag incl restrict demands rel-demands]} (correspondence-of dtag)]
    (let [obj      (case incl
                     :sub [{:demand :total}]
                     :sup [{:demand :surjective :pred restrict}]
                     :eq  [{:demand :total} {:demand :surjective :pred restrict}])
          identity (derive-identity-demand dtag fact-tag (:slots (structure-by-tag dtag))
                                           (set (map :rel rel-demands)))]
      (cond-> (into obj demands) identity (conj identity)))))

(defn ^:export sdef->declarations
  "Adapt an sdef (built by the unchanged parser) into typed declaration maps for the registry — a
   pure re-expression of the sdef's fields PLUS any external correspondence registered for its tag
   (`correspondence-of`); the parser is untouched. `:kind :kind` is the node-kind membership Term,
   emitted only for CONCRETE structures (not realized/coproduct/derived concepts)."
  [{:keys [tag slots laws realized-as relation-coproduct relation-character derived-rule] :as _sdef}]
  (concat
   (when-not (or realized-as relation-coproduct relation-character derived-rule) [{:kind :kind}])
   (for [sl slots] {:kind :slot :slot sl})
   (for [sl slots :when (:transitive sl)]   {:kind :transitive :slot sl})
   (when realized-as       [{:kind :realized-as :body realized-as}])
   (when relation-coproduct [{:kind :coproduct :members relation-coproduct}])
   (when relation-character [{:kind :relation-character :opts relation-character}])
   (when derived-rule      [{:kind :defrelation :rule derived-rule}])
   ;; external correspondence (the sole correspondence surface, an inverted-dependency hook): expand the
   ;; registered `(correspond Design Fact …)` config into declaration maps scoped to this design tag —
   ;; the cross-tag twin/demands as :correspondence, each relation map `(rel incl E)` as :relation-map.
   ;; The fact-side SLOTS are no longer here: they belong to the codomain's own `defstructure`.
   (when-let [{:keys [fact-tag bridge rel-demands]} (correspondence-of tag)]
     (concat
      [{:kind :correspondence :corresponds {:fact-tag fact-tag :bridge bridge
                                            :demands (effective-node-demands tag)}}]
      (for [d rel-demands] {:kind :relation-map :demand d})))
   (for [law laws] {:kind :free-law :law law})))

(defn- parse-demand
  "Parse a `(realized …)`/`(covered …)`/`(agrees …)` corresponds sub-form → a demand map. Allowed
   option keys: realized → :key :desc :when :require ; covered → :key :desc :when :unless ; agrees →
   :key :desc :when :by :over. `:by` (required) selects the per-twin-pair comparator: the built-in
   `:structural` (two twins agree iff their targets over the `:over` slots are IDENTICAL by eid — the
   kernel builds the index, `:over` a vector of slot keywords, required with `:structural`) or a
   registered comparator key (`register-comparator!`, the arbitrary escape hatch — no `:over`). Datalog
   vectors (:when/:require/:unless) pass through unquote-lit. Anything else throws, naming the form."
  [sname f]
  (let [[dk opts] [(keyword (first f)) (second f)]
        allowed   #{:key :desc :when :by :over}]
    (when-not (= dk :agrees)
      (throw (ex-info (str "correspond " sname ": unknown node-demand sub-form " (pr-str (first f))
                           " (the object map rides the :total / :surjective-onto flags)") {:form f})))
    (when (and opts (not (map? opts)))
      (throw (ex-info (str "correspond " sname ": (agrees …) options must be a map") {:form f})))
    (doseq [k (keys opts)]
      (when-not (allowed k)
        (throw (ex-info (str "correspond " sname ": (agrees …) does not take " k " — allowed: " allowed) {:form f :key k}))))
    (when-not (:by opts)
      (throw (ex-info (str "correspond " sname ": (agrees …) needs :by <comparator-key>") {:form f})))
    (when (and (= :structural (:by opts)) (not (seq (:over opts))))
      (throw (ex-info (str "correspond " sname ": (agrees {:by :structural …}) needs :over [slot…]") {:form f})))
    {:demand dk
     :key  (:key opts) :desc (:desc opts) :by (:by opts) :over (:over opts)
     :when (unquote-lit (:when opts))}))

(defn- parse-bridge
  "Parse a `(bridge <arg>)` carrier: a strategy KEYWORD (`:qualified-suffix`, `:exact`) the kernel
   `name-match` builtin lowers (the declarative common case), or a SYMBOL naming a Clojure fn (the
   escape hatch — must RESOLVE at expansion + carry a registered Cozo predicate port)."
  [cname barg]
  (if (keyword? barg)
    barg
    (if-let [v (resolve barg)]
      (symbol (str (ns-name (:ns (meta v)))) (name (:name (meta v))))
      (throw (ex-info (str "correspond " cname ": bridge " barg " does not resolve") {:bridge barg})))))

(defn- parse-sort-map
  "Parse ONE sort-map entry of a `(correspond …)` block — `(DesignSort :incl FactExpr carrier? nested…)`
   — into a per-design-tag config. `:incl` is the OBJECT MAP's inclusion: `:sub` (⊑ total — every design
   node has a twin), `:sup` (⊒ surjective — every fact node in the codomain has a preimage), `:eq` (≡ a
   bijection). `FactExpr` is the codomain: a bare fact tag, or `[FactSort :test]` restricting it to a
   sub-sort (`[Fn :public]`). The carrier is `(bridge …)` for ROOT sorts (name-match) or absent =
   NESTED (paired by name within twinned containers). Nested sub-forms are the RELATION maps
   `(rel :incl E)` and the `(agrees {:by …})` comparator escape hatch."
  [cname incl ftag restrict rest-forms]
  (when-not ('#{:sub :sup :eq} incl)
    (throw (ex-info (str "correspond " cname ": sort map needs an inclusion (:sub / :sup / :eq), got "
                         (pr-str incl)) {:incl incl})))
  (let [seq-subs    (filter seq? rest-forms)
        bridge-sub  (first (filter #(= 'bridge (first %)) seq-subs))
        bridge      (when-let [barg (second bridge-sub)] (parse-bridge cname barg))
        agrees-subs (filter #(= 'agrees (first %)) seq-subs)          ; the comparator escape hatch
        demands     (mapv #(parse-demand cname %) agrees-subs)
        rel-subs    (remove #(or (= 'bridge (first %)) (= 'agrees (first %))) seq-subs)
        ;; a relation map `(rel :incl E)`: an inclusion + a relation-algebra expression over fact
        ;; relations. Compile E eagerly so a malformed expression throws HERE (naming the correspond).
        rel-demands (mapv (fn [[rel rincl expr]]
                            (when-not ('#{:sub :sup :eq} rincl)
                              (throw (ex-info (str "correspond " cname ": relation map " (pr-str rel)
                                                   " needs an inclusion (:sub / :sup / :eq) then an expression, got "
                                                   (pr-str rincl)) {:rel rel :incl rincl})))
                            (reach-clause expr '?_a '?_b)   ; validate E eagerly (throws here, naming the correspond)
                            {:rel (keyword rel) :incl rincl :expr expr})
                          rel-subs)]
    {:fact-tag ftag :incl incl :restrict restrict :bridge bridge :demands demands :rel-demands rel-demands}))

(defmacro correspond
  "Declare ONE design sort's map in the design→fact signature morphism — the concept's `defstructure`
   never mentions correspondence (inverted dependency); this lives in the extractor for a language.
   The form is a SORT map `Design :incl fact-expression`, its relation maps nested:

     (correspond Operation :eq [Fn :public]              ; SORT map — object map onto Fn's public sub-sort
       (:delegates :sub (calls-roll-up…))                ;   RELATION maps, nested
       (:performs  :sup (…))
       (agrees {:by …})?)                                ;   the comparator escape hatch, if any
     (correspond Module :eq Ns (bridge :qualified-suffix))  ; another sort's map (per design element)

   `:incl` is the object map's inclusion (`:sub` total / `:sup` surjective / `:eq` both); the codomain
   is a fact tag or `[FactSort :test]` (restricted to a sub-sort). Relation maps head with a keyword.
   Shared sorts (Schema/Effect — one node both strata) need no `correspond`; their identity map is derived."
  [design incl fexpr & rest]
  (let [dtag                (resolve-struct-tag design)
        [fact-sym restrict] (if (vector? fexpr) [(first fexpr) (second fexpr)] [fexpr nil])
        ftag                (resolve-struct-tag fact-sym)
        config              (parse-sort-map (str design) incl ftag restrict rest)]
    `(register-correspondence! ~dtag '~config)))

(defn- parse-law
  "(law \"desc\" :offenders '[?vars] :where '[clauses] :rules '[rules]? :scope <tag|:global>?)
   — or `(law \"desc\" (combinator …))`, expanded by `combinator-law`.

   :scope controls auto-scoping of the first offender var to a structure:
   absent → the owning structure (the common case: a law about my own
   instances); a tag → that structure (a law whose subject is a related
   structure); :global → no auto-scope (the law is fully explicit)."
  [form]
  (let [[_ desc & kvs] form]
    (if (and (seq? (first kvs)) (symbol? (ffirst kvs)))
      ;; a combinator law: the combinator form, optionally followed by a single :key (the one
      ;; law-level option a combinator can't express — a worklist reader addresses the law by it).
      (let [law  (combinator-law desc (first kvs))
            opts (apply hash-map (rest kvs))]
        (doseq [k (keys opts)]
          (when-not (= k :key)
            (throw (ex-info (str "a combinator law takes the combinator form and an optional :key: "
                                 (pr-str form))
                            {:form form}))))
        (cond-> law (:key opts) (assoc :key (:key opts))))
      (let [m (apply hash-map kvs)]
        {:desc      desc
         :key       (:key m)
         :offenders (unquote-lit (:offenders m))
         :where     (expand-clauses (unquote-lit (:where m)))
         :rules     (unquote-lit (:rules m))
         :scope     (:scope m)}))))

(defmacro defstructure
  "Define a structure: its slots (relations-with-laws) and free laws. Registers the
   structure-definition and defines a VALUE-RETURNING instantiation macro named `sname`.
   The generated macro returns an `InstanceValue` record: scalar slots go into `:scalars`,
   relation slots into `:clauses` as `{:rk :card :targets [...]}` where each symbol target
   is captured as `(var sym)` (a deferred var reference, safe for forward/cyclic refs).

   Slots are ONE map of `rel → type-expr`; cardinality is a quantifier (bare = one,
   `:?` optional, `:*` zero+ ordered, `:+` one+ ordered, `:set` unordered) — see
   `parse-slot-entry`:

     (defstructure Function \"...\"
       {:takes [:* Type]
        :gives Type}
       (law \"...\" :offenders '[?f] :where '[...] :rules '[...]?))

   Instantiate with the generated macro — the instance surface MIRRORS defstructure:
   a name symbol (the var AND the entity name; `^{:name \"…\"}` meta overrides), an
   optional docstring, ONE {slot → value} map, then nested member instances where
   defstructure's laws would sit. A plural slot takes a vector (authoring order is
   the sequence order); a labelled target is a `[label target]` pair; a payload
   slot takes `[value payload]`:
     (Function load-model \"doc\" {:takes [[src String] [out String]] :gives Model})
   The same form without the symbol is an anonymous EXPRESSION instance (inline
   values, def-wrapped instances): (Function \"doc\"? {slot → value}?)

   Body forms must be the slots map or (law ...) / (reader ...) / (syntax ...) /
   (realized-as ...); anything else is rejected
   at macro-expansion time (a silently-dropped form is a footgun). Correspondence is
   declared EXTERNALLY via `(correspond Target …)`, never inside the defstructure.

   A law's :rules may be recursive, including rule-calls-rule (Cozo computes the
   fixpoint with semi-naive evaluation); keep them tight — they re-run on every check."
  [sname docstring & body]
  (doseq [form body]
    (when-not (or (map? form)
                  (and (seq? form) (#{'law 'reader 'syntax 'realized-as} (first form))))
      (throw (ex-info (str "defstructure " sname ": unknown body form " (pr-str form)
                           " — expected a slots map, (law ...), (reader ...), (syntax ...) or (realized-as ...)")
                      {:structure sname :form form}))))
  (when (> (count (filter map? body)) 1)
    (throw (ex-info (str "defstructure " sname ": multiple slots maps — declare all slots in one map")
                    {:structure sname})))
  (let [value? (boolean (:value (meta sname)))
        tag    (keyword (str (ns-name *ns*)) (name sname))   ; identity = defining ns + name
        slots  (mapv (fn [[rel v]] (parse-slot-entry rel v)) (or (first (filter map? body)) {}))
        _      (doseq [s slots]
                 (when (and (scalar-slot? s) (#{:some :many :set} (:card s)))
                   (throw (ex-info
                           (str "defstructure " sname ": scalar slot " (:rel s)
                                " must be bare (one) or [:? ...] (optional), not [:"
                                (name (:card s)) " ...]")
                           {:structure sname :slot (:rel s) :card (:card s)}))))
        laws   (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body))
        explicit-reader (some (fn [f] (when (= 'reader (first f)) (second f)))
                              (filter #(= 'reader (first %)) body))
        ;; a ^:value structure with exactly one scalar slot and no explicit (reader …) is a
        ;; LITERAL ATOM: authoring a bare scalar (`:io`) fills that slot. The synthesized reader
        ;; is VERBATIM — the kernel stores the literal as-is (it knows no scalar types, so it
        ;; cannot coerce), hence the slot's declared type must match the authored literal. The
        ;; degenerate-case dual of the auto-generated scalar type-check law.
        reader-form (or explicit-reader
                        (when (and value? (= 1 (count slots)) (scalar-slot? (first slots)))
                          (let [slot-sym (symbol (name (:rel (first slots))))]
                            `(fn [lit#] [(list '~slot-sym lit#)]))))
        ;; an instance-level authoring-syntax fn (the reader's analogue, raised from a single
        ;; slot's literal to the whole instance arg-tail): applied to the body before clause
        ;; parsing, so a structure owns its surface sugar (e.g. Operation's `->`) — NOT core.
        syntax-form (some (fn [f] (when (= 'syntax (first f)) (second f)))
                          (filter #(= 'syntax (first %)) body))
        realized (some (fn [f] (when (= 'realized-as (first f)) (unquote-lit (second f))))
                       (filter #(= 'realized-as (first %)) body))
        _      (when realized
                 (when (or (seq slots) (seq laws) value? reader-form)
                   (throw (ex-info (str "defstructure " sname
                                        ": a realized concept (realized-as) is pure derived membership —"
                                        " it may not also declare slots, laws, a reader, or ^:value")
                                   {:structure sname})))
                 (when (> (count (filter #(and (seq? %) (= 'realized-as (first %))) body)) 1)
                   (throw (ex-info (str "defstructure " sname ": multiple (realized-as …) forms")
                                   {:structure sname}))))
        sdef   {:tag tag :doc docstring :slots slots :laws laws :value? value?
                :realized-as realized}]
    `(do
       (register-structure! (cond-> '~sdef
                              ~reader-form (assoc :reader ~reader-form)
                              ~syntax-form (assoc :syntax ~syntax-form)))
       ~(cond
          realized nil                                   ; realized concept: no constructor
          value?   `(defmacro ~sname ~docstring [& body#]
                      (fukan.canvas.core.structure/value-form ~tag body#))
          :else    `(defmacro ~sname ~docstring [& args#]
                      (if (symbol? (first args#))
                        ;; def-emitting + nesting: `(Tag sym "doc"? {…} nested…)` interns the var
                        (cons 'do (:defs (fukan.canvas.core.structure/expand-instance ~tag args#)))
                        ;; expression form: `(Tag "doc"? {…})` — anonymous / def-wrapped
                        (fukan.canvas.core.structure/instance-form ~tag args#)))))))

(defmacro defrelation-coproduct
  "Declare a relation as the COPRODUCT (union) of existing relation kinds:
   `(V ?a ?b) ⇐ (kᵢ ?a ?b)` for each member kᵢ. Registers a vocab entry carrying
   `:relation-coproduct`; the `:coproduct` handler emits the union rules so laws/lenses can read
   the umbrella relation `V` at domain altitude. It is the relation-level analogue of a
   `realized-as` coproduct (one level up, over `:rel/kind` instead of node kinds): it has
   no slots, laws, constructor, or instances. Members must be live relation kinds — i.e.
   relation slots present somewhere in the loaded vocab — else the union rule references an
   undefined rule.

     (defrelation-coproduct :view-map \"cross-view mapping\" :via :contextualizes)"
  [rtag docstring & members]
  (let [tag        (keyword (name rtag))
        member-kws (mapv (comp keyword name) members)]
    `(register-structure! {:tag ~tag :doc ~docstring :ns ~(str *ns*) :slots [] :laws []
                           :relation-coproduct ~member-kws})))

(defmacro defrelation
  "Declare a RELATION as an ELEMENT — the relation itself, not a slot that happens to use it.
   Two forms, one concept:

   CHARACTER (a map) — a PRIMITIVE relation: its edges come from the `:rel/kind` of whatever
   structures declare a slot of this name; this declaration owns its CHARACTER, i.e. how it
   relates to OTHER relations. Options:
     `:isa <genus>`     this relation is a SPECIES of `<genus>` — emits the subsumption rule
                        `(genus ?a ?b) ⇐ (rel ?a ?b)`, so every law over the genus sees this
                        relation's edges for free.
     `:transitive true` emits the `rel+` closure.
   Character is a property of the RELATION, not of any one slot — declare it ONCE here and
   every structure using the relation inherits it. (Before relations were elements this rode
   a slot's props map, so `:child`'s containment had to be repeated on every structure
   declaring a `:child` slot — three times, for one rule.)

     (defrelation :contains \"membership — the genus\" {:transitive true})
     (defrelation :child    \"internal membership\"    {:isa :contains})

   DERIVED (a head + a where) — a named datalog rule with a CUSTOM body, injected into
   every law and every `vocab-rules` query at domain altitude (by `check`, exactly as the
   vocab-derived kind/relation rules are). It is the custom-body generalization of a slot's
   relation rule and of `realized-as` (derived UNARY membership), and the open-bodied sibling
   of `defrelation-coproduct` (a relation that is a UNION of existing relation kinds): it has
   no slots, laws, constructor, or instances — only the rule. So a join several laws would
   each re-inline (the module-dependency graph, say) is expressed ONCE here, and the laws just
   call it by name (e.g. `(module-depends ?m ?n)`) instead of repeating the clauses.

   `head` is the rule's argument vector; each following body is a clause-vector — ONE body → one rule,
   MULTIPLE bodies → several rules sharing the head, i.e. a RECURSIVE relation (a base clause + a step
   clause that calls the relation). Bodies may reference other injected rules (`in-module`, `named`, …),
   call predicates, and negate (`(not (public ?m))`). Prefer non-recursive: a vocab-injected rule is
   folded into EVERY law and query, so a recursive one re-evaluates on every check (Cozo terminates via
   its semi-naive fixpoint, but pays it each time).

     (defrelation :public-call \"a reaches b through only non-public interior — the public call graph\"
       '[?a ?b] '[(calls ?a ?b)]                                    ; base: a direct call
                '[(calls ?a ?m) (not (public ?m)) (public-call ?m ?b)])  ; step: through a ¬public node

   A head arg may be an AGGREGATE application — `'[?m (count ?op)]` — making the derived
   relation a MEASURE: a relation targeting a computed scalar, the derived-side mirror of a
   scalar slot (declared relations already target scalar leaves). Plain head vars group;
   supported aggregates are count/sum/min/max/mean. Name a measure only when a consumer
   earns it — the inline `(measure …)` clause is the compositional default.

     (defrelation :produces \"an authored Operation ?o whose :out schema is a ref naming Kind ?k\"
       '[?o ?k]
       '[(design ?o)
         [?or :rel/from ?o] [?or :rel/kind :out] [?or :rel/to ?sch]
         [?sch :val/kind \"ref\"] [?sch :val/ref ?nm] [?k :entity/name ?nm]])"
  [rtag docstring & body]
  (let [tag  (keyword (name rtag))
        ;; `:ns` records the DECLARING namespace: a relation's tag is unqualified (its rule name is
        ;; global), so the tag alone cannot say which vocabulary owns it — and anything scoping by tag
        ;; namespace (the declarations golden) would silently skip every relation element.
        base {:tag tag :doc docstring :ns (str *ns*) :slots [] :laws []}]
    (if (map? (first body))
      `(register-structure! ~(assoc base :relation-character (first body)))
      (let [[head & wheres] body]
        `(register-structure! ~(assoc base :derived-rule
                                      {:head   (list 'quote (unquote-lit head))
                                       ;; one body → one rule; MULTIPLE bodies → multiple rules with the
                                       ;; same head (a RECURSIVE relation: a base clause + a step clause).
                                       :bodies (mapv #(list 'quote (unquote-lit %)) wheres)}))))))

;; ── laws: slot-derived + free, run over a db ─────────────────────────────────

(defn- relation-slot-laws
  "Cardinality + target-type laws for a RELATION slot (target is a structure).
   When `target` is `:Any` (the wildcard), the target-type law is skipped —
   any node is accepted; only cardinality laws are emitted."
  [tag {:keys [rel card target]}]
  (let [tn (name tag) rn (name rel)
        target-law {:desc (str tn "." rn " target must be a " (name target))
                    :offenders '[?x ?t]
                    :where [['?r :rel/from '?x] ['?r :rel/kind rel] ['?r :rel/to '?t]
                            ['?x :structure/of tag]
                            (list 'not ['?t :structure/of target])]}
        none-law (fn [verb]
                   {:desc (str tn "." rn " " verb " (found none)")
                    :offenders '[?x]
                    :where [['?x :structure/of tag]
                            (list 'not-join ['?x]
                                  ['?r :rel/from '?x] ['?r :rel/kind rel])]})
        several-law (fn [verb]
                      {:desc (str tn "." rn " " verb " (found several)")
                       :offenders '[?x]
                       :where [['?x :structure/of tag]
                               ['?r1 :rel/from '?x] ['?r1 :rel/kind rel]
                               ['?r2 :rel/from '?x] ['?r2 :rel/kind rel]
                               [(list 'not= '?r1 '?r2)]]})]
    (cond-> (if (= target :Any) [] [target-law])
      (= card :one)      (conj (none-law "requires exactly one")
                               (several-law "requires exactly one"))
      (= card :some)     (conj (none-law "requires at least one"))
      (= card :optional) (conj (several-law "allows at most one")))))

(defn- value-slot-laws
  "Type-check (+ none for `one`) laws for a VALUE slot (target is a type form — a scalar
   keyword or a refined vector). Every target is passed verbatim to the dialect's
   `value-valid?`; the kernel never interprets type forms. No 'found several' law: plain
   :val/<key> storage is cardinality-one."
  [tag {:keys [rel card target]}]
  (let [tn (name tag) rn (name rel)
        val-attr (keyword "val" (name rel))
        type-law {:desc (str tn "." rn " value must satisfy " (pr-str target))
                  :offenders '[?x]
                  :where [['?x :structure/of tag]
                          ['?x val-attr '?v]
                          [(list 'fukan.canvas.core.typing/value-valid? target '?v) '?ok]
                          [(list 'false? '?ok)]]}
        none-law {:desc (str tn "." rn " requires exactly one (found none)")
                  :offenders '[?x]
                  :where [['?x :structure/of tag]
                          (list 'not-join ['?x] ['?x val-attr '?_v])]}]
    (cond-> [type-law]
      (= card :one) (conj none-law))))

;; ── correspondence demand laws: generated from (corresponds …) declarations ──
;;
;; ⚠ KNOWN LEAK (2026-07-17, deliberate — do not "defend" it, fix it). The NESTED `twin` rule further
;; down still NAMES the vocabulary relation `contains`: it hardcodes the morphism's CARRIER ("same name
;; within twinned containers"). It resolves when the carrier becomes an authored parameter rather than a
;; name the kernel knows (`:by (name-within Module Ns)`); until then the kernel ships this much
;; vocabulary, and it only resolves because the vocab happens to declare a genus called `contains`.
;; (The other half of this leak — a cross-container guard welded into a "generic" law — went away with
;; the legacy `realized-by-laws`: delegates' realization is now the roll-up relation map, and its
;; fidelity is an architectural concern at the Subsystem altitude, not a guard in here.)
;;
;; The NODE-demand SHAPES (design↔fact). Bodies inline the stratum literal (:val/extracted — see
;; substrate/stratum-attr's sync note) for range-boundedness and call the injected twin rule. Guard
;; discipline: plain realized guards on ∃ fact instance OF THIS KIND (small set, no cartesian multiply);
;; realized-with-:require binds the twin POSITIVELY (a missing twin is plain realized's offence — no
;; double-fire); covered needs no guard (its subject IS a fact instance).

(defn- demand-key
  "A demand's full stable law key: :corresponds/<Short>.<local>."
  [tag local]
  (keyword "corresponds" (str (name tag) "." (name local))))

(defn- node-demand-law
  "One generated law map for a node-level demand — a design instance of `dtag` and its fact twin of
   `ftag` (the codomain). Cross-tag: the design side is bound `:structure/of dtag`, the fact side
   `:structure/of ftag`. `:val/extracted` is kept alongside the tags — belt-and-suspenders (a fact
   node IS of a distinct extracted-only tag), and it lets a same-tag correspondence (`ftag = dtag`, an
   identity-on-shared-sort like Schema) still split the two strata by provenance. The stable law key
   rides `dtag`, so keys stay `:corresponds/<Design>.*`."
  [dtag ftag {:keys [demand key desc when pred by over]}]
  (let [k (demand-key dtag (or key demand))]
    (case demand
      ;; the OBJECT MAP is total: every design instance has a fact twin (guarded on ∃ extracted fact of
      ;; this kind, so it is vacuously green before any code is extracted).
      :total
      {:key k :offenders '[?x]
       :desc (or desc (str (name dtag) ": the object map is total — every design instance has a fact twin"))
       :where (vec (concat [['?_g :structure/of ftag] '[?_g :val/extracted true]
                            ['?x :structure/of dtag] '(not [?x :val/extracted true])]
                           when
                           ['(not-join [?x] (twin ?x ?t))]))}
      ;; the OBJECT MAP is surjective onto the codomain — every fact instance has a design preimage.
      ;; `pred` (the codomain restriction, e.g. Fn's `public` test) narrows the codomain to a sub-sort;
      ;; without it, every fact instance of the sort must have a preimage.
      :surjective
      {:key k :offenders '[?x]
       :desc (or desc (str (name dtag) ": the object map is surjective onto "
                           (if pred (str "the " (clojure.core/name pred) " sub-sort") (name ftag))
                           " — every such fact instance has a design preimage"))
       :where (vec (concat [(if pred (list (symbol (clojure.core/name pred)) '?x) ['?x :structure/of ftag])
                            '[?x :val/extracted true]]
                           when
                           ['(not-join [?x] (twin ?s ?x))]))}
      :agrees
      ;; a PAIR-HYBRID law: :where enumerates the design instance + its fact twin (+ any :when guard);
      ;; the check engine runs the registered `:by` comparator over each pair and offends where false.
      {:key k :offenders '[?x]
       :desc (or desc (str (name dtag) " (" (clojure.core/name (or key demand))
                           "): every design instance's fact twin agrees via " by))
       :comparator (cond-> {:by by :on '[?x ?t]} over (assoc :over over))
       :where (vec (concat [['?x :structure/of dtag] '(not [?x :val/extracted true]) '(twin ?x ?t)]
                           when))})))

;; ── the relation-map primitive: a design relation ↦ a fact expression ─────────
;; The morphism's non-identity component, authored as `(R incl E)` where `incl` is the relation
;; INCLUSION ordering the map asserts — `:sub` (⊑, preserve: R ⊆ E), `:sup` (⊒, reflect: R ⊇ E),
;; `:eq` (≡, both) — and `E` is a relation-algebra term over fact relations (regular relations / the
;; Kleene-algebra operators, spelled as the regex AST malli itself uses):
;;   :r  atom · [:+ E] closure · [:* E] refl-closure · [:? E] optional · [:cat E…] path · [:alt E…] union
;; This replaces the ad-hoc `:realized-by`/`:covered-from`/`:faithful` keyword grab-bag: each was a
;; point (E, direction) in this one algebra. Grounding: SPARQL 1.1 property paths / regular path
;; queries for E; the relation-inclusion order for the direction. (`expr->path-segments`, the lowering
;; to the `path` builtin, lives up by `expand-clauses` — the parser needs it eagerly.)
(defn- twinned-target?
  "True when the design relation `rel` on `tag` targets a CROSS-TAG-corresponded sort (its instances
   have distinct fact twins — like `:delegates` → Operation ↦ Fn), as opposed to a SHARED sort whose
   design and fact nodes are one (`:performs` → Effect). Decides the reflect law's target check: a
   reached fact node is matched to the design edge's target directly (shared) or through its twin."
  [tag rel]
  (when-let [target (:target (slot-for (structure-by-tag tag) rel))]
    (let [c (correspondence-of target)]
      (boolean (and c (:fact-tag c) (not= (:fact-tag c) target))))))

(defn- relation-map-laws
  "The generated law(s) for a relation map `(rel incl E)` on `tag`. `E` is a regular-relation expression
   over fact relations, lowered to a `reach-clause` binding two nodes (a complex `E` is a NAMED fact
   `defrelation`, referenced as an atom). The INCLUSION picks which homomorphism condition(s) to enforce:
     :sub (⊑, preserve) — every design `rel` edge is realized by an `E` reach between the endpoints' twins.
     :sup (⊒, reflect)  — every fact node the twin reaches over `E` is declared on the design side.
     :eq  (≡)           — both.
   Preserve binds BOTH endpoints positively via `twin` (an untwinned endpoint is the totality demand's
   concern, and the law is vacuously green before extraction). Reflect matches the reached fact node to
   the design edge's target directly for a SHARED sort, or through its twin for a twinned sort."
  [tag {:keys [rel incl expr]}]
  (let [twinned? (twinned-target? tag rel)
        preserve {:key (demand-key tag (str (name rel) "-realized"))
                  :desc (str (name tag) "." (name rel) ": every design edge is realized by a "
                             (pr-str expr) " reach between the endpoints' twins")
                  :offenders '[?a]
                  :where [['?dr :rel/from '?a] ['?dr :rel/kind rel] ['?dr :rel/to '?b]
                          '(twin ?a ?ea) '(twin ?b ?eb)
                          (list 'not-join '[?ea ?eb] (reach-clause expr '?ea '?eb))]}
        reflect  {:key (demand-key tag (str (name rel) "-covered"))
                  :desc (str (name tag) "." (name rel) ": every fact node the twin reaches via "
                             (pr-str expr) " is declared on the design side")
                  :offenders '[?x]
                  :where [['?x :structure/of tag] '(not [?x :val/extracted true])
                          '(twin ?x ?t)
                          (reach-clause expr '?t '?v)
                          (if twinned?
                            (list 'not-join '[?x ?v]
                                  ['?dr :rel/from '?x] ['?dr :rel/kind rel] ['?dr :rel/to '?b]
                                  '(twin ?b ?v))
                            (list 'not-join '[?x ?v]
                                  ['?dr :rel/from '?x] ['?dr :rel/kind rel] ['?dr :rel/to '?v]))]}]
    (case incl
      :sub [preserve]
      :sup [reflect]
      :eq  [preserve reflect])))

;; ── built-in declaration handlers ────────────────────────────────────────────
;; Each existing construct as a registered handler emitting Terms and/or Laws — the SOLE rule/law
;; emitter. Both production seams dispatch through here: `laws-of` (the law side) and
;; `vocab-rules`→`terms-of` (the term side). `fukan.canvas.core.rules` now holds only the fixed
;; substrate rules `terms-of` composes in; the declarations-golden test freezes this emission.

(defn- rule-sym [kw] (symbol (name kw)))

(defn- closure-rules
  "The transitive-closure rules for a relation NAME `rname` — `(R+ a b) ⇐ (R a b) ∪ (R a m)(R+ m b)`."
  [rname]
  (let [r+ (symbol (str (name rname) "+"))]
    [[(list r+ '?a '?b) (list rname '?a '?b)]
     [(list r+ '?a '?b) (list rname '?a '?mid) (list r+ '?mid '?b)]]))

;; Registered with `register-declaration!` + a plain `fn` (not the `defdeclaration` sugar) so the
;; kernel's own handlers lint natively; `defdeclaration` is the project-facing growth macro.
(register-declaration! :kind
  (fn [_ sdef]
    {:terms [[(list (rule-sym (:tag sdef)) '?e) ['?e :structure/of (:tag sdef)]]] :laws []}))

(register-declaration! :slot
  (fn [{:keys [slot]} sdef]
    (let [tag (:tag sdef)]
      (if (scalar-slot? slot)
        {:terms [] :laws (value-slot-laws tag slot)}
        {:terms [[(list (rule-sym (:rel slot)) '?a '?b)
                  ['?r :rel/from '?a] ['?r :rel/kind (:rel slot)] ['?r :rel/to '?b]]]
         :laws  (relation-slot-laws tag slot)}))))

(register-declaration! :transitive
  (fn [{:keys [slot]} _sdef] {:terms (closure-rules (rule-sym (:rel slot))) :laws []}))

;; A relation ELEMENT's character — how it relates to OTHER relations. `:isa` emits the subsumption
;; rule `(genus ?a ?b) ⇐ (rel ?a ?b)` (so every law over the genus sees the species' edges);
;; `:transitive` emits the closure. The genus name comes from the DECLARATION — the kernel names no
;; relation of its own.
(register-declaration! :relation-character
  (fn [{:keys [opts]} sdef]
    (let [r (rule-sym (:tag sdef))]
      {:terms (cond-> []
                (:isa opts)        (conj [(list (rule-sym (:isa opts)) '?a '?b) (list r '?a '?b)])
                (:transitive opts) (into (closure-rules r)))
       :laws []})))


(register-declaration! :realized-as
  (fn [{:keys [body]} sdef]
    {:terms [(into [(list (rule-sym (:tag sdef)) '?e)] body)] :laws []}))

(register-declaration! :coproduct
  (fn [{:keys [members]} sdef]
    {:terms (vec (for [m members]
                   [(list (rule-sym (:tag sdef)) '?a '?b) (list (rule-sym m) '?a '?b)]))
     :laws []}))

(register-declaration! :defrelation
  (fn [{:keys [rule]} sdef]
    (let [head (apply list (rule-sym (:tag sdef)) (:head rule))]
      {:terms (mapv (fn [body] (into [head] body)) (:bodies rule)) :laws []})))

(register-declaration! :relation-map
  (fn [{:keys [demand]} sdef] {:terms [] :laws (relation-map-laws (:tag sdef) demand)}))

(register-declaration! :correspondence
  (fn [{:keys [corresponds]} sdef]
    ;; the twin is a CROSS-TAG map: design `dtag` ↦ fact `ftag` (the codomain). A ROOT kind pairs by
    ;; name-bridge; a NESTED kind pairs same-named instances whose containers already twin. `:val/extracted`
    ;; still marks the fact side (so `ftag = dtag` degenerates to an identity-on-shared-sort correspondence).
    (let [dtag (:tag sdef)
          ftag (:fact-tag corresponds)]
      {:terms [(if-let [bridge (:bridge corresponds)]
               [(list 'twin '?a '?b)
                ['?a :structure/of dtag] (list 'not ['?a :val/extracted true])
                ['?b :structure/of ftag] ['?b :val/extracted true]
                ['?a :entity/name '?an] ['?b :entity/name '?bn]
                ;; a strategy KEYWORD lowers through the generic `name-match` builtin; a SYMBOL is a
                ;; vocab fn-predicate lowered through its registered port
                [(if (keyword? bridge) (list 'name-match bridge '?an '?bn) (list bridge '?an '?bn))]]
               [(list 'twin '?a '?b)
                ['?a :structure/of dtag] (list 'not ['?a :val/extracted true])
                ['?b :structure/of ftag] ['?b :val/extracted true]
                ['?a :entity/name '?n] ['?b :entity/name '?n]
                (list 'contains '?ca '?a) (list 'contains '?cb '?b)
                (list 'twin '?ca '?cb)])]
       :laws (mapv #(node-demand-law dtag ftag %) (:demands corresponds))})))

(register-declaration! :free-law (fn [{:keys [law]} _sdef] {:terms [] :laws [law]}))

(defn ^:export terms-of
  "All derived Terms over `structures` via the declaration handlers + the fixed substrate rules
   (`rules/substrate-rules`) — the SOLE term emitter, dispatched by `vocab-rules`. The
   declarations-golden test freezes its self-model output.

   The kernel names NO relation of its own: a containment genus, its closure, and any relation
   derived from it (`in-module`) are declared by the VOCABULARY as relation elements
   (`defrelation`), not emitted here. (Until 2026-07-17 this hardcoded the symbol `contains`, its
   closure, and `in-module` — code vocabulary welded into the kernel.)"
  [structures]
  (vec (distinct (concat (mapcat (fn [sdef]
                                   (mapcat #(:terms (handle-declaration % sdef)) (sdef->declarations sdef)))
                                 structures)
                         rules/substrate-rules))))

(defn ^{:malli/schema [:=> [:cat [:sequential :any]] :map]}
  correspondence*
  "The correspondence SEAM of `sdefs` as one data structure — the collected morphism (pure;
   `correspondence` applies it to the live registry). {:kinds tag→{:bridge :demands},
   :relations [{:owner :rel …demand options… :keys}], :keys full-key→source-pointer}. Assembling
   the `:keys` index GUARDS cross-family key collisions (two declarations deriving one law key
   would silently union their offender sets in `violations-of`) — it throws, naming both sources.
   The A↔B surface-neutral core: an alternative authoring surface (a `defcorrespondence` block)
   would WRITE what this READS."
  [sdefs]
  ;; correspondence is EXTERNAL — registered by `(correspond Tag …)` against the tag — so the seam reads
  ;; it from the registry (`correspondence-of`), in lockstep with the laws `sdef->declarations` emits.
  ;; Relation demands come from a structure's own slots ∪ the external `:rel-demands`.
  (let [kinds     (into {}
                        (for [{:keys [tag]} sdefs
                              :let [c (correspondence-of tag)]
                              :when c]
                          [tag (-> (select-keys c [:bridge])
                                   (assoc :demands (effective-node-demands tag))
                                   (update :demands
                                           (fn [ds] (mapv #(assoc % :key (demand-key tag (or (:key %) (:demand %)))) ds))))]))
        ;; a relation map `(rel incl E)`: keyed + directioned by its inclusion.
        relations (vec
                   (for [{:keys [tag]} sdefs
                         {:keys [rel incl expr]} (:rel-demands (correspondence-of tag))]
                     {:owner tag :rel rel :incl incl :expr expr
                      :keys (case incl
                              :sub [(demand-key tag (str (name rel) "-realized"))]
                              :sup [(demand-key tag (str (name rel) "-covered"))]
                              :eq  [(demand-key tag (str (name rel) "-realized"))
                                    (demand-key tag (str (name rel) "-covered"))])}))
        rel-dirs  (fn [r] (case (:incl r) :sub [:preserve] :sup [:reflect] :eq [:preserve :reflect]))
        entries   (concat
                   (for [[tag {:keys [demands]}] kinds, d demands]
                     [(:key d) {:owner tag :via :node :demand (dissoc d :key)}])
                   ;; key/direction zip truncates correctly: each demand family's (:keys r) and direction vec align.
                   (for [r relations, [k dir] (map vector (:keys r) (rel-dirs r))]
                     [k {:owner (:owner r) :via :relation :rel (:rel r) :direction dir}]))
        keys*     (reduce (fn [acc [k src]]
                            (when (contains? acc k)
                              (throw (ex-info (str "duplicate correspondence law key " k
                                                   " — derived by both " (pr-str (acc k))
                                                   " and " (pr-str src))
                                              {:key k :first (acc k) :second src})))
                            (assoc acc k src))
                          {} entries)]
    {:kinds kinds :relations relations :keys keys*}))

(defn ^{:malli/schema [:=> [:cat] :map]}
  correspondence
  "The live registry's correspondence seam — see `correspondence*`."
  []
  (correspondence* (all-structures)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  laws-of
  "Every law of structure `sdef` — the slot-derived cardinality/type laws plus its
   correspondence-demand laws (generated from `(realized …)`/`(covered …)` sub-forms)
   plus its free `(law …)`s, the same set `check` runs. Public so an alternative engine
   (the Cozo law compiler) can evaluate the identical laws. Dispatched through the declaration
   handlers (no consumer depends on law order — every one treats the result as a set)."
  [sdef]
  (vec (mapcat #(:laws (handle-declaration % sdef)) (sdef->declarations sdef))))

(defn ^{:malli/schema [:=> [:cat [:vector :any]] :any]}
  direct-scope-tags
  "Qualified tags whose instances carry `:structure/of` DIRECTLY, so a law scoped to one can be
   pinned ns-precisely (`[?o :structure/of tag]`) instead of riding the short-name rule. Excludes
   realized/coproduct/derived concepts (no instances). For these direct tags two same-short-named
   structures from different namespaces never cross-scope."
  [structures]
  (into #{}
        (comp (remove #(or (:realized-as %) (:relation-coproduct %) (:derived-rule %)))
              (map :tag))
        structures))

(defn ^{:malli/schema [:=> [:cat] [:vector :Rule]]}
  vocab-rules
  "The datalog rules derived from the live vocabulary (one per kind + per relation
   slot, plus the fixed substrate rules). Lets queries — and laws (via `check`) — read
   at domain altitude: `(Operation ?s) (in-module ?s \"…\") (calls ?s ?c)`. Emitted through the
   declaration handlers via `terms-of` — the same registry seam the law side (`laws-of`) dispatches
   through; the declarations-golden test freezes this seam's self-model output."
  []
  (terms-of (all-structures)))

;; ── evaluation lives in the ENGINE, not here ─────────────────────────────────
;; `check` (run every law → violations) + its worklist readers (`violations-of`/`violation-names`)
;; live in `fukan.cozo.law`. The kernel DEFINES laws (`laws-of`/`all-structures`); the engine
;; EVALUATES them and depends on the kernel one way — so there is no `structure ↔ law` cycle and no
;; registry. (This was a hollow kernel `check` shell dispatching to a registered backend; that
;; indirection papered over the cycle and is gone.)

;; ── correspondence comparator SPI ─────────────────────────────────────────────
;; A `(corresponds … (agrees {:by <key>}))` demand gates a per-twin-pair AGREEMENT whose comparison
;; is not datalog-expressible (e.g. type-signature adherence). The comparator is a registered
;; `(fn [db design-eid fact-eid] → bool)` — it owns BOTH extracting each side's comparable value AND
;; comparing them, so the kernel's correspondence machinery stays agnostic to what is compared (the
;; law engine just runs the registered comparator over the twin pairs the demand enumerates). Fourth
;; instance of the kernel's delegation pattern (typing plug-point, predicate-port, value-valid? hybrid).
(defonce ^:private comparators (atom {}))

(defn ^:export ^{:malli/schema [:=> [:cat :keyword :any] :nil]}
  register-comparator!
  "Register a correspondence COMPARATOR: `key → (fn [db design-eid fact-eid] → boolean)`. An
   `(agrees {:by key})` demand runs it over each twin pair; a false result is a violation.
   Re-registering a key replaces it."
  [key f] (swap! comparators assoc key f) nil)

(defn ^:export ^{:malli/schema [:=> [:cat :keyword] :any]}
  comparator-for
  "The registered comparator fn for `key`, or nil."
  [key] (@comparators key))
