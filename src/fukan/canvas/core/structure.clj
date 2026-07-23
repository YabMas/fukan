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
            [clojure.walk :as walk]
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
   datalog rule name is global — so its NAME is global presentation identity: re-declaring the same relation
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

(defn- slot-for
  "The slot descriptor for `rel` on `sdef`'s structure. An extracted instance's fact-side slots
   (`:calls`/`:private`/…) are now its OWN defstructure's slots — the codomain (`Fn`/`Ns`) is a real
   structure — so this reads `sdef` directly; no external graft to fold in."
  [sdef rel]
  (first (filter #(= rel (:rel %)) (:slots sdef))))

(defn- sdef-syntax
  "The optional inline `(syntax …)` elaborator for `sdef`. The slots map remains the canonical
   instance form; a syntax hook is vocabulary-local sugar which must return that map."
  [sdef] (:syntax sdef))

;; ── value authoring ──────────────────────────────────────────────────────────

(defn- unquote-lit [v] (if (and (seq? v) (= 'quote (first v))) (second v) v))

;; ── authoring clause composition ─────────────────────────────────────────────

(defn- dvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- and-disjunct [clauses]
  (case (count clauses)
    0 (throw (ex-info "path expansion produced an empty disjunct" {}))
    1 (first clauses)
    (apply list 'and clauses)))

(defn- zero-admitting?
  "True when the expression `E` can match the EMPTY path (zero hops) — a bare `[:* r]`/`[:? r]`,
   or a `:cat`/`:alt` whose parts all/any can. A zero-admitting `[:alt …]` BRANCH has no inline
   lowering (its zero case would be an `=`-unification an or-join branch cannot host)."
  [e]
  (cond
    (or (keyword? e) (symbol? e)) false
    (and (vector? e) (seq e))
    (case (first e)
      (:* :?) true
      :+      false
      :not    true
      :inv    false
      :cat    (every? zero-admitting? (rest e))
      :alt    (boolean (some zero-admitting? (rest e)))
      false)
    :else false))

(declare path-steps)

(defn- path-steps*
  "One expression → its step list, or throw naming the whole `expr` (the caller's context)."
  [e expr]
  (cond
    (or (keyword? e) (symbol? e)) [[(symbol (name e)) :one]]
    (and (vector? e) (seq e))
    (let [[op & args] e]
      (case op
        :cat       (vec (mapcat #(path-steps* % expr) args))
        :alt       [[:alt (vec args)]]
        (:+ :* :?) (let [[inner] args]
                     (when-not (or (keyword? inner) (symbol? inner))
                       (throw (ex-info (str "path: " op " over a compound expression needs a NAMED "
                                            "defrelation (its recursion lives with the relation): "
                                            (pr-str expr)) {:expr expr})))
                     [[(symbol (name inner)) ({:+ :one+, :* :zero+, :? :zero-one} op)]])
        :not       (let [[pred] args]
                     (when-not (or (keyword? pred) (symbol? pred))
                       (throw (ex-info (str "path: [:not pred] takes a unary relation/predicate name: "
                                            (pr-str e)) {:expr expr})))
                     [[:not (symbol (name pred))]])
        :inv       (let [[inner] args]
                     (when-not (or (keyword? inner) (symbol? inner))
                       (throw (ex-info (str "path: [:inv r] inverts a relation ATOM only — name a compound "
                                            "as a defrelation first: " (pr-str e)) {:expr expr})))
                     [[(symbol (name inner)) :one :inv]])
        (throw (ex-info (str "path: " (pr-str e) " is not a regular-relation expression — an atom, "
                             "[:cat …], [:alt …], or [:+ r]/[:* r]/[:? r]"
                             (when (keyword? op)
                               (str " (the suffix segments [:r :s* :t+] are retired — spell the AST:"
                                    " [:calls* :performs] is [:cat [:* :calls] :performs])")))
                        {:expr expr}))))
    :else (throw (ex-info (str "path: unreadable expression " (pr-str e)) {:expr expr}))))

(defn- path-steps
  "Normalize a regular-relation expression `E` — the ONE path language, the malli-regex AST also
   used by correspondence relation maps and inclusion elements (an atom, `:cat`, `:alt`,
   `:+`/`:*`/`:?` over atoms) — into the linear STEP list the clause compiler walks: `[rule quant]`
   (quant one of `:one`/`:one+`/`:zero+`/`:zero-one`) or `[:alt [E …]]` for a branch step. Closure
   over a compound expression has no inline lowering — that recursion is a NAMED `defrelation`."
  [expr]
  (path-steps* expr expr))

(declare path-clauses*)

(defn- path-hop-or-skip
  "The or-join for a zero-admitting step: { skip: the rest of the path straight from `from`,
   hop: `hop-rule` (the closure `r+` for `[:* r]`, one `r` edge for `[:? r]`) then the rest from
   its target }. The rest is FUSED into both branches, so the skip case never needs a bare
   `=`-unification against an unbound fresh var."
  [from hop-rule rest-steps to fresh]
  (let [mid (fresh)
        join-vars (vec (distinct (filter dvar? [from to])))
        zero (path-clauses* from rest-steps to fresh)
        plus (cons (list hop-rule from mid)
                   (path-clauses* mid rest-steps to fresh))]
    (list 'or-join join-vars (and-disjunct zero) (and-disjunct plus))))

(defn- path-clauses*
  "Compile a step list into datalog clauses. `[:* r]` means zero or more hops, so
   `[:cat [:* :calls] :performs]` expands to direct `:performs` OR `calls+` followed by
   `:performs`; an `[:alt …]` step is an or-join whose every branch positively binds its target.
   A `[:not pred]` step is zero-width — it filters the CURRENT position and does not advance,
   so it may not be the final step (nothing would bind `to`). An `:inv` marker on a `:one` hop
   reverses the rule's argument order (an inverse traversal of the relation)."
  [from steps to fresh]
  (if (empty? steps)
    [(list '= from to)]
    (let [[step & more*] steps
          more   (seq more*)
          final? (nil? more)
          target (if final? to (fresh))]
      (if (= :not (first step))
        (if final?
          (throw (ex-info "path: [:not pred] cannot end a path — it filters a position, it doesn't bind one"
                          {:step step}))
          (cons (list 'not (list (second step) from))
                (path-clauses* from more to fresh)))
        (if (= :alt (first step))
          (let [alts (second step)]
            (doseq [e alts]
              (when (zero-admitting? e)
                (throw (ex-info (str "path: an [:alt …] branch must make at least one hop — "
                                     (pr-str e) " admits the empty path; name that relation "
                                     "(defrelation) or restructure the alternative") {:branch e}))))
            (cons (apply list 'or-join (vec (distinct (filter dvar? [from target])))
                         (map #(and-disjunct (path-clauses* from (path-steps %) target fresh)) alts))
                  (when-not final? (path-clauses* target more to fresh))))
          (let [[rule q inv?] step]
            (case q
              :one
              (cons (list rule (if inv? target from) (if inv? from target))
                    (when-not final? (path-clauses* target more to fresh)))

              :one+
              (cons (list (symbol (str (name rule) "+")) from target)
                    (when-not final? (path-clauses* target more to fresh)))

              :zero+
              [(path-hop-or-skip from (symbol (str (name rule) "+")) more to fresh)]

              :zero-one
              [(path-hop-or-skip from rule more to fresh)])))))))

(defn ^:export expand-clauses
  "Expand authoring-layer composition clauses into ordinary datalog clauses.

   Supported forms:
     `(path ?from E ?to)` — relational composition over the ONE regular-relation expression
     language (the same E a correspondence relation map and an inclusion element state): an atom
     `:calls`, `[:cat E …]`, `[:alt E …]`, `[:+ r]`/`[:* r]`/`[:? r]` over atoms. `:*` is
     zero-or-more (includes the zero-hop case), `:?` zero-or-one; both fuse the path's remainder
     into their or-join. (The old suffix-segment vectors `[:r :s* :t+]` are retired.)

   Expansion is top-level only, matching the existing `(via …)` behavior."
  [clauses]
  (vec
   (apply concat
          (map-indexed
           (fn [i clause]
             (if (and (seq? clause) (= 'path (first clause)))
               (let [[_ from expr to] clause]
                 (when-not (= 4 (count clause))
                   (throw (ex-info (str "path clause must be (path ?from E ?to): " (pr-str clause))
                                   {:clause clause})))
                 (let [counter (atom 0)
                       fresh (fn []
                               (symbol (str "?_path" i "_" (swap! counter inc))))]
                   (path-clauses* from (path-steps expr) to fresh)))
               [clause]))
           clauses))))

(defn- path-lowerable?
  "True when `E` lowers inline through the `path` clause, safe wherever the law binds its
   endpoints: an atom; `:cat` of lowerables; `:+`/`:*`/`:?` over atoms; `:alt` whose every branch
   is lowerable AND makes at least one hop (a zero-admitting branch would need `=`-unification an
   or-join branch cannot host). Beyond this — closure over a compound — the expression is a NAMED
   derived relation instead."
  [expr]
  (cond
    (or (keyword? expr) (symbol? expr)) true
    (and (vector? expr) (seq expr))
    (case (first expr)
      :cat       (every? path-lowerable? (rest expr))
      :alt       (every? #(and (path-lowerable? %) (not (zero-admitting? %))) (rest expr))
      (:+ :* :?) (keyword? (second expr))
      :not       (let [[pred] (rest expr)] (or (keyword? pred) (symbol? pred)))
      :inv       (let [[inner] (rest expr)] (or (keyword? inner) (symbol? inner)))
      false)
    :else false))

(defn- reach-clauses
  "The datalog clauses binding `from`→`to` over the fact expression `E` — a regular-relation term
   (an atom, `:cat`, `:+`/`:*` over atoms), lowered through the `path` builtin. A star-headed path
   folds into ONE `or-join` clause, but a plain `:cat` of atoms lowers to one clause PER HOP — the
   caller must splice all of them (taking only the first silently dropped every later hop). An `E`
   that needs its OWN recursion (the public call graph) is a NAMED fact relation — a `defrelation` —
   referenced here as an atom, so the complex definition lives with the relation, not inline in the
   correspondence."
  [expr from to]
  (when-not (path-lowerable? expr)
    (throw (ex-info (str "correspond: expression " (pr-str expr) " is not a regular-relation path — "
                         "name it a `defrelation` and reference it as an atom") {:expr expr})))
  (expand-clauses [(list 'path from expr to)]))

(defn- incl-rule-bodies
  "Lower an inclusion expression `E` to defining rule BODIES from `?a` to `?b` (a `:sup`/`:eq`
   relation element is DEFINED from E): an atom or path-lowerable expression → one body of its
   expanded clauses; `[:alt E …]` → one body per alternative. Beyond the regular fragment, the
   declaration must be DERIVED (head + body) instead — thrown eagerly, naming the element."
  [tag expr]
  (cond
    (and (vector? expr) (= :alt (first expr)))
    (vec (mapcat #(incl-rule-bodies tag %) (rest expr)))
    (path-lowerable? expr)
    [(expand-clauses [(list 'path '?a expr '?b)])]
    :else
    (throw (ex-info (str "defrelation " tag ": inclusion expression " (pr-str expr)
                         " is beyond the regular fragment — declare the relation DERIVED "
                         "(head + body) instead") {:tag tag :expr expr}))))

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

(defn- map-entry->clause
  "One slots-map entry `slot → value` → the internal clause form. The encoding is
   schema-driven — the slot's declared quantifier/payload disambiguates the value:

     :one/:optional    bare value         (k v)         a `[label target]` pair stays one element
     :many/:some/:set  vector of targets  (k v1 v2 …)   the bracket mirrors the quantifier
     payload slot      [value payload]    (k value payload)

   Every key must be a declared slot: if a value becomes a fact, its meaning comes from vocabulary."
  [tag sdef [k v]]
  (let [slot (slot-for sdef k)
        head (symbol (clojure.core/name k))]
    (when-not slot
      (throw (ex-info (str (clojure.core/name tag) ": `" (clojure.core/name k) "` is not a slot")
                      {:tag tag :rel k})))
    (cond
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
  "Shared clause-walker behind `value-form` and named `expand-instance`.
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
        scalar? (fn [c] (let [s (slot-for sdef (keyword (first c)))] (and s (scalar-slot? s))))
        scalars (into {} (for [c clauses :when (scalar? c)
                               :let [slot (slot-for sdef (keyword (first c)))]
                               pair (cond-> [[(keyword "val" (clojure.core/name (first c)))
                                              (if (:form slot)
                                                (list 'quote (unquote-lit (second c)))
                                                (second c))]]
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
                              ;; a union slot's targets are plain refs — no single reader applies
                              target-sdef (when (and slot (nil? (:alts slot)))
                                            (structure-by-tag (:target slot)))]
                          (when-not slot
                            (throw (ex-info (str (clojure.core/name tag) ": `"
                                                 (clojure.core/name rk) "` is not a slot")
                                            {:tag tag :rel rk})))
                          (let [[labels target-forms] (parse-clause-arg-forms (rest c) target-sdef)]
                            (rel-map-form rk (:card slot) target-forms labels))))
                      (remove scalar? clauses))]
    `(->InstanceValue ~tag ~name-expr ~doc ~scalars ~rels ~value?-expr)))

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
   tag — or whose union of alternatives (`:alts`) admits it — unless `private?`, then the
   `Any`-targeting fallback (the internal :child slot)."
  [sdef kid-tag private?]
  (or (when-not private?
        (some #(when (or (= (:target %) kid-tag)
                         (some #{kid-tag} (:alts %)))
                 (:rel %))
              (:slots sdef)))
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
     :child [:* Op Kind Node]          zero or more, a UNION of sorts
     :item  [:+ Item]                  one or more, ordered
     :field [:set Field]               zero or more, unordered identity
     :mode  [:enum \"a\" \"b\"]            a refined scalar, cardinality one

   A quantifier takes malli's props position for slot options: `[:? {:payload :q} :string]`;
   for the default card, lead with the props map: `[{:payload :q} :string]`.
   The target form: a SYMBOL resolves to a structure tag (a ref-slot; `Any` is the
   wildcard). A KEYWORD or VECTOR is a TYPE FORM (a value-slot): stored verbatim and
   never interpreted by the kernel — the generated law checks values through the
   registered type dialect (`fukan.canvas.core.typing/value-valid?`).

   A UNION target lists ALTERNATIVE structure refs after the quantifier (or props map):
   `[:* A B C]` / `[{} A B]`. Structure refs only (no scalars, no `Any` — a union of
   anything is `Any`); `:target` holds the first alternative, `:alts` the full vector,
   and the generated target-type law checks the disjunction."
  [rel v]
  (let [[card props forms] (cond
                             (and (vector? v) (contains? quantifiers (first v)))
                             (let [props (when (map? (second v)) (second v))]
                               [(quantifiers (first v)) props (vec (drop (if props 2 1) v))])
                             (and (vector? v) (map? (first v)))
                             [:one (first v) (vec (rest v))]
                             :else [:one nil [v]])
        form   (first forms)
        union? (> (count forms) 1)
        _ (when union?
            (when-not (every? symbol? forms)
              (throw (ex-info (str "slot " rel ": a union target lists structure refs only — got "
                                   (pr-str v))
                              {:rel rel :form v})))
            (when (some #(= 'Any %) forms)
              (throw (ex-info (str "slot " rel ": Any in a union is just Any — " (pr-str v))
                              {:rel rel :form v}))))
        type-form? (and (not union?)
                        (or (keyword? form) (vector? form)))  ; symbol → structure-ref; else → a type form
        target (cond
                 (symbol? form)  (resolve-struct-tag form)
                 (and (not union?) (vector? form))  form
                 (and (not union?) (keyword? form)) (keyword (name form))
                 :else (throw (ex-info (str "slot " rel ": unreadable type expression " (pr-str v))
                                       {:rel rel :form v})))]
    (merge (cond-> {:rel rel :card card :target target :type-form? type-form?}
             union? (assoc :alts (mapv resolve-struct-tag forms)))
           props)))

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
   `'[(design ?x) [?xr :rel/kind :child] [?xr :rel/to ?x]]`). nil → none."
  [var when]
  (cond
    (nil? when) []
    (map? when)  (mapv (fn [[k v]] [var (keyword "val" (clojure.core/name k)) v]) when)
    :else        (vec (unquote-lit when))))

(defn- exemption-clauses
  "A combinator's `:unless` exemption → NEGATED where-clauses on `var`. A scalar MAP `{k v}`
   is sugar for `(not [var :val/k v])` per entry; a raw datalog clause-VECTOR has each clause
   negated (`(not c)`). nil → none."
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
   (spliced positive / negated, using `?x` as the subject); :from constrains the matching
   counterpart's structure; :scope (a structure symbol) hosts the law about ANOTHER
   structure's instances (default: self-scoped to the owner)."
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

(defn ^:export sdef->declarations
  "Adapt an sdef (built by the unchanged parser) into typed declaration maps for the registry — a
   pure re-expression of the sdef's fields; the parser is untouched. `:kind :kind` is the node-kind
   membership Term, emitted only for CONCRETE structures (not realized/coproduct/derived concepts).
   (Cross-tag correspondence is a separate declaration form — `correspond`, below — lowered on its
   own path through `terms-of`, not merged in here.)"
  [{:keys [slots laws realized-as relation-element relation-incl derived-rule]}]
  (concat
   (when-not (or realized-as relation-element derived-rule) [{:kind :kind}])
   (for [sl slots] {:kind :slot :slot sl})
   (when realized-as   [{:kind :realized-as :body realized-as}])
   (when relation-incl [{:kind :relation-incl :dir (:incl relation-incl) :expr (:expr relation-incl)}])
   (when derived-rule  [{:kind :defrelation :rule derived-rule}])
   (for [law laws] {:kind :free-law :law law})))

;; ── (is ?v Sort) — declaration-site sort resolution ───────────────────────────
;; The ns-PRECISE dual of a bare kind-rule call: `(is ?m Module)` pins ?m to MY Module — the
;; sort resolved at declaration time through the declaring ns's vars (the same requires-based
;; identity an instance reference uses) — where `(Module ?m)` reads ANY co-loaded Module (the
;; deliberate union). Resolution is PARSE-side (the declaring ns is live; the phase line holds —
;; only the signature is consulted); the LOWERING — a `:structure/of` triple for a direct tag,
;; the kind-rule call for a realized/facet concept — is the query compiler's
;; (`fukan.cozo.query/compile-clause`), so a resolved `(is ?v <tag>)` works uniformly in law
;; bodies, rule bodies, and evaluated contexts (which pass the qualified tag — a bare symbol
;; resolves only in a declaration form). A sort must be declared BEFORE the clause that names
;; it (resolution is lexical): use `::Sort` for a same-ns forward reference, the full tag
;; keyword where a require would cycle.

(def ^:private ^:dynamic *self-tag*
  "The tag of the structure currently being DEFINED, bound while its laws parse — so a law may
   say `(is ?s MyOwnSort)` before its own registration exists (the self-reference case)."
  nil)

(defn- resolve-is-clause
  "Resolve the sort NAME in one `(is ?v Sort)` clause → `(is ?v <qualified-tag>)`. A symbol
   resolves self-tag first (the defining scope), then by var (requires-based), then by a
   same-ns registered tag (realized concepts intern no var); a keyword passes through
   (validated at compile). Anything else — or an unresolvable symbol — throws, naming the sort."
  [c]
  (if-not (and (seq? c) (= 'is (first c)))
    c
    (let [[_ v target] c]
      (when-not (= 3 (count c))
        (throw (ex-info (str "(is …) wants (is ?var Sort): " (pr-str c)) {:clause c})))
      (cond
        (keyword? target) c
        (symbol? target)
        (let [via-self (when (and *self-tag* (= (name target) (name *self-tag*))) *self-tag*)
              via-var  (when-let [vr (resolve target)]
                         (let [m (meta vr)
                               t (keyword (str (ns-name (:ns m))) (name (:name m)))]
                           (when (structure-by-tag t) t)))
              via-ns   (let [t (keyword (str *ns*) (name target))]
                         (when (structure-by-tag t) t))
              tag      (or via-self via-var via-ns)]
          (when-not tag
            (throw (ex-info (str "(is " v " " target "): no structure named " target " resolves here"
                                 " — require its defining namespace (resolution rides requires, like"
                                 " an instance reference), or use ::" target " / the full tag keyword"
                                 " for a forward or cyclic reference")
                            {:clause c :sort target})))
          (list 'is v tag))
        :else
        (throw (ex-info (str "(is …) sort must be a symbol (resolved here) or a qualified tag"
                             " keyword: " (pr-str c)) {:clause c}))))))

(defn- resolve-sorts
  "Walk `clauses` — into not / not-join / or-join / and / measure containers — resolving every
   `(is ?v Sort)` sort symbol to its qualified tag (`resolve-is-clause`)."
  [clauses]
  (mapv (fn [c]
          (let [c (resolve-is-clause c)]
            (if-not (seq? c)
              c
              (let [[h & args] c]
                (cond
                  ('#{not and} h)          (apply list h (resolve-sorts (vec args)))
                  ('#{not-join or-join} h) (apply list h (first args) (resolve-sorts (vec (rest args))))
                  (= 'measure h)           (apply list h (first args) (second args)
                                                  (resolve-sorts (vec (drop 2 args))))
                  :else c)))))
        clauses))

;; ── the essential correspondence: two queries + a realization map ─────────────
;; `(correspond [Design ?d Fact ?f] match-body {rel → E})` — the WHOLE bridge declaration.
;; Head = identity (sorts constitutive, design first). Match body = flat identity logic (may
;; reference the ambient `corresponds` — recursion; acyclicity is the author's obligation, a
;; cycle fails at evaluation). Map = TOTAL over the design sort's non-scalar slots; entries are
;; PURE code-graph paths; `nil` = declared-unrealized. Lowers EXCLUSIVELY to rules (pairing,
;; realized-<rel>, per-^:value reflexivity) — definitional, no denials (THE TEST). The term
;; emission (`entry-rules`/`correspond-terms`/`value-reflexivity-terms`) rides with `closure-rules`
;; below; this section is the registry + the authoring macro.

(defonce ^:private corresponds-registry (atom {}))

(defn ^:export register-correspond!
  "Register a correspond config keyed by its sort pair. The completeness guard runs FIRST — every
   non-scalar design slot needs an entry (or nil) — so an under-specified map fails on its own
   terms regardless of who declares it. Then every non-nil entry expression must make at least one
   hop: a ZERO-ADMITTING E (one that matches the empty path — a bare `[:* r]`/`[:? r]`, or a `:cat`
   all of whose parts admit it) is the identity, which would mint an ungroundable reflexive
   `realized-<rel>` rule (a bare `(= from to)` unification Cozo cannot ground), so it throws here.
   Aux extraction preserves zero-admittance, so the raw entry is the faithful test. Then cross-ns
   re-registration THROWS (mirrors `register-structure!`'s relation guard); same-ns replaces (REPL reload)."
  [{:keys [design fact map ns] :as config}]
  (let [k [design fact]]
    (when-let [sdef (structure-by-tag design)]
      (doseq [sl (remove scalar-slot? (:slots sdef))]
        (when-not (contains? map (:rel sl))
          (throw (ex-info (str "correspond " k ": design slot " (:rel sl)
                               " has no realization entry — state its code path, or nil for "
                               "declared-unrealized") {:pair k :slot (:rel sl)})))))
    (doseq [[rel expr] map :when expr]
      (when (zero-admitting? expr)
        (throw (ex-info (str "correspond " k ": realization entry " rel " ↦ " (pr-str expr)
                             " admits the empty path — the zero case is the identity — state the atom"
                             " or restructure") {:pair k :slot rel :expr expr}))))
    (when-let [prior (get @corresponds-registry k)]
      (when (not= (:ns prior) ns)
        (throw (ex-info (str "correspondence " k " is already declared by " (:ns prior))
                        {:pair k :declared-by (:ns prior) :redeclared-by ns}))))
    (swap! corresponds-registry assoc k config)
    k))

(defn ^{:malli/schema [:=> [:catn [:pair :any]] :any]}
  correspond-by-pair [pair] (get @corresponds-registry pair))
(defn ^{:malli/schema [:=> [:cat] [:vector :any]]}
  all-corresponds [] (vals @corresponds-registry))

(defmacro correspond
  "Declare a correspondence — the ENTIRE bridge between a design sort and a fact sort:

     (correspond [Operation ?op Fn ?fn]
       [(named ?op ?n) (named ?fn ?n)
        (contains ?m ?op) (contains ?ns ?fn) (corresponds ?m ?ns)]
       {:in :in  :out :out
        :performs  [:cat [:* :calls] :performs]
        :delegates [:cat :calls [:* [:cat [:not public] :calls]]]})

   Pairing joins into the ambient `corresponds`; each entry mints `realized-<rel>` (an E-path
   between witnesses — a `^:value` node is its own witness); coverage classes are READINGS,
   not laws. Checks are ordinary laws over these rules, authored separately."
  [head match rmap]
  (when-not (and (vector? head) (= 4 (count head))
                 (symbol? (nth head 0)) (dvar? (nth head 1))
                 (symbol? (nth head 2)) (dvar? (nth head 3)))
    (throw (ex-info "correspond head must be [DesignSort ?d FactSort ?f]" {:head head})))
  (let [[dsym dvar fsym fvar] head
        dtag  (resolve-struct-tag dsym)
        ftag  (resolve-struct-tag fsym)
        match (resolve-sorts match)]         ; the SAME (is …) pass law bodies get
    `(register-correspond! {:design ~dtag :fact ~ftag :dvar '~dvar :fvar '~fvar
                            :match '~match :map '~rmap :ns (str *ns*)})))

(defn- parse-law
  "(law \"desc\" {:offenders [?vars] :where [clauses] :rules [rules]? :scope <tag|:global>? :key k?})
   — or `(law \"desc\" (combinator …) {:key k}?)`, expanded by `combinator-law`.

   The body is ONE map, unquoted — the declaration cell every other form uses; datalog
   inside a declaration form is data by position, so it is never quoted (quotes are for
   evaluated contexts: the REPL, `q`). The retired kwargs style throws, naming the law.

   :scope controls auto-scoping of the first offender var to a structure:
   absent → the owning structure (the common case: a law about my own
   instances); a tag → that structure (a law whose subject is a related
   structure); :global → no auto-scope (the law is fully explicit)."
  [form]
  (let [[_ desc & body] form]
    (if (and (seq? (first body)) (symbol? (ffirst body)))
      ;; a combinator law: the combinator form, optionally followed by an options map holding
      ;; :key (the one law-level option a combinator can't express — worklist readers address
      ;; the law by it).
      (let [law  (combinator-law desc (first body))
            opts (second body)]
        (when (or (> (count body) 2) (and (some? opts) (not (map? opts))))
          (throw (ex-info (str "law " (pr-str desc) ": a combinator law takes the combinator form "
                               "and an optional options map: (law \"desc\" (has :r) {:key :k})")
                          {:form form})))
        (doseq [k (keys opts)]
          (when-not (= k :key)
            (throw (ex-info (str "law " (pr-str desc) ": a combinator's options map takes only :key, got " k)
                            {:form form :key k}))))
        (cond-> (update law :where resolve-sorts)
          (:key opts) (assoc :key (:key opts))))
      (let [m (first body)]
        (when (keyword? m)
          (throw (ex-info (str "law " (pr-str desc) ": the kwargs body is retired — one unquoted map: "
                               "(law \"desc\" {:offenders [?x] :where […]})")
                          {:form form})))
        (when-not (and (map? m) (empty? (rest body)))
          (throw (ex-info (str "law " (pr-str desc) ": the body is one map or one combinator form: "
                               (pr-str form))
                          {:form form})))
        (doseq [k (keys m)]
          (when-not (#{:key :offenders :where :rules :scope} k)
            (throw (ex-info (str "law " (pr-str desc) ": unknown law option " k
                                 " — allowed: :offenders :where :rules :scope :key")
                            {:form form :key k}))))
        {:desc      desc
         :key       (:key m)
         :offenders (unquote-lit (:offenders m))
         :where     (expand-clauses (resolve-sorts (unquote-lit (:where m))))
         :rules     (some->> (unquote-lit (:rules m))
                             (mapv (fn [[h & b]] (into [h] (resolve-sorts (vec b))))))
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
       (law \"...\" {:offenders [?f] :where [...] :rules [...]?}))

   Instantiate an ENTITY with the generated macro — the surface mirrors defstructure:
   a name symbol (the var AND the entity name; `^{:name \"…\"}` meta overrides), an
   optional docstring, ONE {slot → value} map, then nested member instances where
   defstructure's laws would sit. A plural slot takes a vector (authoring order is
   the sequence order); a labelled target is a `[label target]` pair; a payload
   slot takes `[value payload]`:
     (Function load-model \"doc\" {:takes [[src String] [out String]] :gives Model})
   Entity instances always require the name symbol. Only `^:value` structures are
   anonymous, content-identified expressions: `(Effect :io)`, `(Schema {:kind …})`.

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
        laws   (binding [*self-tag* tag]
                 (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body)))
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
        realized (some (fn [f] (when (= 'realized-as (first f)) (resolve-sorts (unquote-lit (second f)))))
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
                        (throw (ex-info (str ~(name sname) ": entity instances require a name symbol; "
                                             "only ^:value structures are anonymous")
                                        {:tag ~tag :args args#}))))))))

(defmacro defrelation
  "Declare a RELATION as an ELEMENT — the relation itself, not a slot that happens to use it.
   Three forms, one construct:

   BARE (name + doc only) — a PRIMITIVE relation: its edges come from the `:rel/kind` of whatever
   structures declare a slot of this name, or from other relations' inclusions INTO it (a genus
   is a bare element its species declare `(:sub …)` toward). Its head is explicitly OPEN. The
   declaration claims the name (global presentation identity — a second namespace re-declaring it
   throws), reflects, and owns the doc.

     (defrelation :contains \"membership — the genus\")

   INCLUSION (a `(direction expr)` list) — the relation stated as an INCLUSION against an
   expression over other relations, the SAME triple a correspondence relation map uses:
     `(:sub E)`  this relation ⊑ E — every edge of this relation is an E edge. E must be a
                 relation ATOM; the lowering is GENERATIVE (`(E ?a ?b) ⇐ (rel ?a ?b)`), so a
                 law over the including relation sees this one's edges for free.
     `(:sup E)` / `(:eq E)` — this relation ⊒/≡ E: DEFINED from E (`(rel ?a ?b) ⇐ E-clauses`).
                 E is a regular-relation expression — an atom, `[:alt E …]` (one rule per
                 alternative), or a path (`[:cat …]`, `[:+ r]`, `[:* r]` over atoms). `:sup`
                 contributes E to an OPEN head; `:eq` CLOSES the head and rejects any slot or
                 downstream inclusion that would also feed it. An E beyond the fragment is a
                 DERIVED declaration instead.

     (defrelation :child    \"internal membership\"  (:sub :contains))
     (defrelation :view-map \"cross-view link\"      (:eq [:alt :via :contextualizes]))

   DERIVED (a head + a where) — a named CLOSED datalog view with a CUSTOM body: the general
   definitional extension, for anything the inclusion fragment can't say. The closed-head check
   rejects any other contributor to its name. `head` is the rule's
   argument vector; each following body is a clause-vector — ONE body → one rule, MULTIPLE
   bodies → several rules sharing the head, i.e. a RECURSIVE relation (a base clause + a step
   clause that calls the relation). Bodies may reference other injected rules, call predicates,
   and negate. Prefer non-recursive: a vocab-injected rule is folded into EVERY law and query,
   so a recursive one re-evaluates on every check.

     (defrelation :public-call \"a reaches b through only non-public interior — the public call graph\"
       [?a ?b] [(calls ?a ?b)]                                    ; base: a direct call
               [(calls ?a ?m) (not (public ?m)) (public-call ?m ?b)])  ; step: through a ¬public node

   A head arg may be an AGGREGATE application — `[?m (count ?op)]` — making the derived
   relation a MEASURE. Plain head vars group; supported aggregates are count/sum/min/max/mean.

   TRANSITIVE CLOSURES are not declared at all: `R+`/`R*` belong to the expression language, and
   the compiler emits every binary relation's closure rules unconditionally — a query pays for a
   closure only when it references it (per-query rule injection is reachability-scoped). The old
   `{:isa …}`/`{:transitive true}` character map and `defrelation-coproduct` are retired — the
   inclusion form states both."
  [rtag docstring & body]
  (let [tag  (keyword (name rtag))
        ;; `:ns` records the DECLARING namespace: a relation's tag is unqualified (its rule name is
        ;; global), so the tag alone cannot say which vocabulary owns it — and anything scoping by tag
        ;; namespace (the declarations golden) would silently skip every relation element.
        base {:tag tag :doc docstring :ns (str *ns*) :slots [] :laws [] :relation-element true}
        f    (first body)]
    (cond
      (map? f)
      (throw (ex-info (str "defrelation " tag ": the character map is retired — state the relation as "
                           "an inclusion: {:isa :g} → (:sub :g); {:transitive true} → nothing (closures "
                           "are the compiler's — R+ works wherever it is referenced)")
                      {:tag tag :form f}))
      (empty? body)
      `(register-structure! ~base)
      (and (seq? f) (keyword? (first f)))
      (let [[incl expr] f
            expr (unquote-lit expr)]
        (when-not (#{:sub :sup :eq} incl)
          (throw (ex-info (str "defrelation " tag ": unknown inclusion direction " incl
                               " (one of :sub / :sup / :eq)") {:tag tag :form f})))
        ;; validate the expression EAGERLY, at expansion — a bad E throws here naming the element
        (if (= :sub incl)
          (when-not (keyword? expr)
            (throw (ex-info (str "defrelation " tag ": a within-theory :sub needs a relation ATOM "
                                 "(the included relation accumulates this one's edges); an inclusion "
                                 "into a compound expression is a constraint — state it at the "
                                 "correspondence seam or as a law") {:tag tag :expr expr})))
          (incl-rule-bodies tag expr))
        `(register-structure! ~(assoc base :relation-incl
                                      {:incl incl :expr (list 'quote expr)})))
      :else
      (let [[head & wheres] body]
        `(register-structure! ~(assoc base :derived-rule
                                      {:head   (list 'quote (unquote-lit head))
                                       ;; one body → one rule; MULTIPLE bodies → multiple rules with the
                                       ;; same head (a RECURSIVE relation: a base clause + a step clause).
                                       :bodies (mapv #(list 'quote (resolve-sorts (unquote-lit %))) wheres)}))))))

;; ── laws: slot-derived + free, run over a db ─────────────────────────────────

(defn- relation-slot-laws
  "Cardinality + target-type laws for a RELATION slot (target is a structure).
   When `target` is `:Any` (the wildcard), the target-type law is skipped —
   any node is accepted; only cardinality laws are emitted. A UNION slot
   (`:alts`) checks the disjunction: the target must be NONE of the alternatives
   to offend (a conjunction of `not`s)."
  [tag {:keys [rel card target alts]}]
  (let [tn (name tag) rn (name rel)
        target-law {:desc (str tn "." rn " target must be a "
                               (if alts (str/join "|" (map name alts)) (name target)))
                    :offenders '[?x ?t]
                    :where (into [['?r :rel/from '?x] ['?r :rel/kind rel] ['?r :rel/to '?t]
                                  ['?x :structure/of tag]]
                                 (for [a (or alts [target])]
                                   (list 'not ['?t :structure/of a])))}
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

;; ── closed declaration lowering ──────────────────────────────────────────────
;; Each kernel construct lowers to Terms and/or Laws here — the SOLE rule/law emitter. Both
;; production seams pass through it: `laws-of` (the law side) and
;; `vocab-rules`→`terms-of` (the term side). `fukan.canvas.core.rules` now holds only the fixed
;; substrate rules `terms-of` composes in; the declarations-golden test freezes this emission.

(defn- rule-sym [kw] (symbol (name kw)))

(defn- closure-rules
  "The transitive-closure rules for a relation NAME `rname` — `(R+ a b) ⇐ (R a b) ∪ (R a m)(R+ m b)`."
  [rname]
  (let [r+ (symbol (str (name rname) "+"))]
    [[(list r+ '?a '?b) (list rname '?a '?b)]
     [(list r+ '?a '?b) (list rname '?a '?mid) (list r+ '?mid '?b)]]))

;; ── correspondence term emission (the essential construct's rules) ────────────
;; A registered `(correspond [Design ?d Fact ?f] match {rel → E})` lowers to a PAIRING rule (the
;; ambient open `corresponds` head, guarded by both sorts) + one `realized-<rel>` rule per entry +
;; per-`^:value` reflexivity. All definitional — no denials (checks are ordinary laws over these,
;; authored separately). Read by `terms-of` below, alongside the closure/substrate rules.

(defn- distribute-trailing-closure
  "Cozo cannot ground a standalone or-join helper whose reflexive (zero) branch is a bare `(= from to)`
   unification — neither head var is bound by a relation there (`unbound variable`). A path whose FINAL
   step is a zero-admitting closure lowers to exactly that, so rewrite it to make the zero case reuse
   the preceding hop's binding instead: `[:cat P… [:? X]]` → `[:alt [:cat P…] [:cat P… X]]` and
   `[:cat P… [:* X]]` → `[:alt [:cat P…] [:cat P… [:+ X]]]` — both alternatives now ground `to` through
   a relation. Only the :cat-trailing shape (the delegates roll-up `[:cat :calls [:? aux]]`) needs it;
   any other expression passes through unchanged."
  [expr]
  (if (and (vector? expr) (= :cat (first expr)) (>= (count expr) 3)
           (vector? (peek expr)) (#{:? :*} (first (peek expr))))
    (let [body   (vec (rest expr))
          prefix (subvec body 0 (dec (count body)))
          [q x]  (peek body)
          pfx    (if (= 1 (count prefix)) (first prefix) (into [:cat] prefix))]
      [:alt pfx (into [:cat] (conj prefix (if (= q :?) x [:+ x])))])
    expr))

(defn- entry-rules
  "The rules for one realization entry `rel ↦ expr` of correspondence `c`. The `realized-<rel>`
   rule CONJUGATES the fact-graph path with the pairing relation on both ends — a design edge
   `rel(?d, ?e)` is realized when `?d`'s fact witness reaches `?e`'s fact witness along `expr`.
   Compound closure (`[:* C]`/`[:+ C]` over a compound `C`) is legal HERE — it mints an auxiliary
   recursive rule `<realized-name>-s<i>` for `C+` (one-or-more `C`; base + step, the `public-call`
   shape) and folds the quantifier back around that aux ATOM so no extra `+` closure is needed
   (`:+ ↦ aux`, `:* ↦ [:? aux]`). A resulting TRAILING zero-admitting step is then distributed
   (`distribute-trailing-closure`) so its reflexive case stays groundable. Inline `path` contexts
   still reject compound closure. Each rule's variables are rule-local: the aux rules use `?a/?b/?m`."
  [{:keys [dvar]} rel expr]
  (let [rname (symbol (str "realized-" (name rel)))
        aux-n (atom 0)
        auxes (atom [])
        ;; rewrite each compound closure bottom-up into a minted aux ATOM before the path steps see it
        expr' (walk/postwalk
               (fn [e]
                 (if (and (vector? e) (#{:* :+} (first e)) (vector? (second e)))
                   (let [q    (first e)
                         c    (second e)
                         aux  (symbol (str rname "-s" (swap! aux-n inc)))
                         base (into [(list aux '?a '?b)]                       ; base: one C
                                    (expand-clauses [(list 'path '?a c '?b)]))
                         step (into [(list aux '?a '?b) (list aux '?a '?m)]    ; step: aux then one C
                                    (expand-clauses [(list 'path '?m c '?b)]))]
                     (swap! auxes conj base step)
                     ;; aux ≡ C+, so [:+ C] is the bare aux and [:* C] is an optional aux (= C*)
                     (if (= q :+) (keyword aux) [:? (keyword aux)]))
                   e))
               expr)
        wd    '?_cd     ; the design node's fact witness
        we    '?_cw     ; the realized design node's fact witness
        e     '?_ce]    ; the realized design node
    (into [(into [(list rname dvar e) (list 'corresponds dvar wd)]
                 (concat (reach-clauses (distribute-trailing-closure expr') wd we)
                         [(list 'corresponds e we)]))]
          @auxes)))

(defn- correspond-terms
  "All rules one registered correspondence lowers to: the pairing rule + every entry's rules."
  [{:keys [design fact dvar fvar match] rmap :map :as c}]
  (into [(into [(list 'corresponds dvar fvar)
                [dvar :structure/of design] [fvar :structure/of fact]]
               match)]
        (mapcat (fn [[rel expr]] (when expr (entry-rules c rel expr))) rmap)))

(defn- value-reflexivity-terms
  "One `corresponds(?v ?v)` rule per `^:value` structure — a content-identified value is its own
   witness, which is what makes the pairing relation total on shared sorts (no case analysis)."
  [structures]
  (for [sd structures :when (:value? sd)]
    [(list 'corresponds '?v '?v) ['?v :structure/of (:tag sd)]]))

;; The kernel declaration algebra is deliberately closed. Vocabulary grows through structures,
;; relations, rules, laws, and derived authoring forms—not by installing new evaluator semantics.
;; This exhaustive lowering is therefore both the implementation and the fail-closed boundary.
(defn- lower-declaration
  [{:keys [kind slot dir expr body rule law] :as declaration} sdef]
  (let [tag (:tag sdef)]
    (case kind
      :kind
      {:terms [[(list (rule-sym tag) '?e) ['?e :structure/of tag]]] :laws []}

      :slot
      (if (scalar-slot? slot)
        {:terms [] :laws (value-slot-laws tag slot)}
        {:terms [[(list (rule-sym (:rel slot)) '?a '?b)
                  ['?r :rel/from '?a] ['?r :rel/kind (:rel slot)] ['?r :rel/to '?b]]]
         :laws  (relation-slot-laws tag slot)})

      :relation-incl
      (let [r (rule-sym tag)]
        {:terms (case dir
                  :sub       [[(list (rule-sym expr) '?a '?b) (list r '?a '?b)]]
                  (:sup :eq) (mapv #(into [(list r '?a '?b)] %) (incl-rule-bodies tag expr)))
         :laws []})

      :realized-as
      {:terms [(into [(list (rule-sym tag) '?e)] body)] :laws []}

      :defrelation
      (let [head (apply list (rule-sym tag) (:head rule))]
        {:terms (mapv (fn [rule-body] (into [head] rule-body)) (:bodies rule)) :laws []})

      :free-law
      {:terms [] :laws [law]}

      (throw (ex-info (str "unknown declaration kind " kind)
                      {:declaration declaration :structure tag})))))

(defn- binary-rule-names
  "Every relation name `structures` gives a BINARY rule: non-scalar slot kinds, relation elements
   (bare and inclusion elements are binary by construction), and derived relations with a
   two-plain-var head. `terms-of` emits each one's closure `R+` UNCONDITIONALLY: transitive
   closure belongs to the expression language, so its availability is the COMPILER's business,
   not a declaration's (`{:transitive true}` is retired). Two Horn rules apiece; a query pays
   for a closure only when its reachability closure references it — per-query rule injection is
   already reachability-scoped (`cozo.query/vocab-index`)."
  [structures]
  (->> structures
       (mapcat (fn [sd]
                 (concat (map :rel (remove scalar-slot? (:slots sd)))
                         (if-let [h (:head (:derived-rule sd))]
                           (when (and (= 2 (count h)) (every? dvar? h)) [(:tag sd)])
                           (when (:relation-element sd) [(:tag sd)])))))
       (map rule-sym) (distinct) (sort)))

(defn- relation-head-contributions
  "Every declaration that contributes clauses to a relation head. A contribution marked
   `:definition?` is the owning relation element's own definition; slots and `:sub` inclusions are
   external contributors. Used to make open/closed relation semantics an enforced invariant rather
   than prose."
  [sdef]
  (concat
   (for [slot (remove scalar-slot? (:slots sdef))]
     {:head (:rel slot) :owner (:tag sdef) :via :slot})
   (when-let [{:keys [incl expr]} (:relation-incl sdef)]
     [(if (= :sub incl)
        {:head expr :owner (:tag sdef) :via :sub-inclusion}
        {:head (:tag sdef) :owner (:tag sdef) :via incl :definition? true})])
   (when (:derived-rule sdef)
     [{:head (:tag sdef) :owner (:tag sdef) :via :derived :definition? true}])))

(defn- validate-closed-relation-heads!
  "Reject contributors to a CLOSED relation head other than its own definition. Derived
   `defrelation`s and `(:eq E)` inclusions are closed; bare and `(:sup E)` relation elements are
   open. Returns `structures` for threading."
  [structures]
  (let [closed (into {}
                     (for [sdef structures
                           :when (or (:derived-rule sdef)
                                     (= :eq (:incl (:relation-incl sdef))))]
                       [(:tag sdef) (:tag sdef)]))]
    (doseq [{:keys [head owner definition?] :as contribution}
            (mapcat relation-head-contributions structures)
            :let [closed-owner (closed head)]
            :when (and closed-owner
                       (not (and definition? (= owner closed-owner))))]
      (throw (ex-info (str "closed relation " head " is defined by " closed-owner
                           " and cannot also be fed by " owner " via " (:via contribution))
                      {:relation head :defined-by closed-owner :contribution contribution})))
    structures))

(defn ^:export terms-of
  "All derived Terms over `structures` via closed declaration lowering + every binary relation's
   closure rules (`binary-rule-names`) + the fixed substrate rules (`rules/substrate-rules`) —
   the SOLE term emitter, dispatched by `vocab-rules`. The declarations-golden test freezes its
   self-model output.

   The kernel names NO relation of its own: a containment genus and any relation derived from it
  (`within`) are declared by the VOCABULARY as relation elements (`defrelation`), not emitted
   here. (Until 2026-07-17 this hardcoded the symbol `contains`, its closure, and `in-module` —
   code vocabulary welded into the kernel.)

   Each essential correspondence whose DESIGN sort is in `structures` contributes its pairing/
   `realized-*` rules, and every `^:value` structure its `corresponds(?v ?v)` reflexivity — the
   ambient `corresponds` head those definitional rules union into. Scoping the correspondences by
   in-scope design tag mirrors the per-sdef lowering above (which only sees the sdefs it is
   handed), so a subset call — the declarations golden's `self-model-structures` — stays stable
   regardless of which fixtures polluted the global registry."
  [structures]
  (let [structures (validate-closed-relation-heads! (vec structures))
        in-scope   (into #{} (map :tag) structures)]
    (vec (distinct (concat (mapcat (fn [sdef]
                                     (mapcat #(:terms (lower-declaration % sdef)) (sdef->declarations sdef)))
                                   structures)
                           (mapcat closure-rules (binary-rule-names structures))
                           (mapcat correspond-terms
                                   (filter #(contains? in-scope (:design %)) (all-corresponds)))
                           (value-reflexivity-terms structures)
                           rules/substrate-rules)))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  laws-of
  "Every law of structure `sdef` — the slot-derived cardinality/type laws plus its free
   `(law …)`s, the same set `check` runs. Public so an alternative engine (the Cozo law
   compiler) can evaluate the identical laws. Dispatched through the declaration handlers
   (no consumer depends on law order — every one treats the result as a set). (Correspondence
   generates no laws — the essential `correspond` construct is definitional-only; coverage and
   adherence are READINGS over the `corresponds`/`realized-*` rules `terms-of` emits, not laws.)"
  [sdef]
  (vec (mapcat #(:laws (lower-declaration % sdef)) (sdef->declarations sdef))))

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
   at domain altitude: `(Operation ?s) (within ?s \"…\") (calls ?s ?c)`. Emitted through the
   closed lowering via `terms-of` — the same path the law side (`laws-of`) uses; the
   declarations-golden test freezes this seam's self-model output."
  []
  (terms-of (all-structures)))

;; ── evaluation lives in the ENGINE, not here ─────────────────────────────────
;; `check` (run every law → violations) + its worklist readers (`violations-of`/`violation-names`)
;; live in `fukan.cozo.law`. The kernel DEFINES laws (`laws-of`/`all-structures`); the engine
;; EVALUATES them and depends on the kernel one way — so there is no `structure ↔ law` cycle and no
;; registry. (This was a hollow kernel `check` shell dispatching to a registered backend; that
;; indirection papered over the cycle and is gone.)
