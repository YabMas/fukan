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
  (:require [datascript.core :as d]
            [fukan.canvas.core.rules :as rules]
            ;; the node substrate this grammar sits on (the InstanceValue the macro emits,
            ;; node identity, the empty db) lives one layer down
            [fukan.canvas.core.substrate :as sub :refer [->InstanceValue]]))

;; ── value-slot classification ─────────────────────────────────────────────────
;; A slot is a VALUE slot when its target is a TYPE FORM — a scalar keyword or a
;; refined vector — owned by the type dialect. Classification is purely SYNTACTIC,
;; set at parse time (`:type-form?`). The kernel knows no specific scalar types:
;; every value-slot check is routed through the dialect's `value-valid?`.

(defn scalar-slot?
  "True when a slot is a VALUE slot (its target is a type form — a scalar keyword or a
   refined vector — owned by the type dialect), as opposed to a structure-ref slot.
   Purely syntactic: set at parse time (`:type-form?`); the kernel knows no specific types."
  [slot]
  (boolean (:type-form? slot)))


;; ── structure registry (vocabulary as data: slots + laws, no family/payload) ──

(defonce ^:private structures (atom {}))

(defn ^:export register-structure! [sdef] (swap! structures assoc (:tag sdef) sdef) (:tag sdef))
(defn all-structures [] (vals @structures))
(defn structure-by-tag [tag] (get @structures tag))

;; ── instantiation (the interpreter: instance → Node + reified slot Relations) ─

(defn- slot-for [sdef rel] (first (filter #(= rel (:rel %)) (:slots sdef))))


;; ── value-authoring: instance-form / value-form ──────────────────────────────

(defn- unquote-lit [v] (if (and (seq? v) (= 'quote (first v))) (second v) v))

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
     payload slot      [value payload]    (k value payload)"
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
  (if-let [syn (:syntax sdef)] (syn m) m))

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
        scalar? (fn [c] (let [s (slot-for sdef (keyword (first c)))] (and s (scalar-slot? s))))
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
                  (and (empty? (rest body)) (or (map? one) (:syntax sdef))))
      (throw (ex-info (str (clojure.core/name tag) ": an instance is `("
                           (clojure.core/name tag) " \"doc\"? {slot → value}?)` — got "
                           (pr-str (vec body)))
                      {:tag tag :body (vec body)})))
    (build-instance-form tag nil doc false
                         (map->clauses tag sdef (apply-syntax sdef (if (some? one) one {}))))))

(defn ^:export value-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for a ^:value instance —
   anonymous (name=nil) and content-identified (value?=true). The author surface is
   `(Tag {slot → value})`; a clause-vector body is the internal IR readers emit
   (`reader-arg->form` calls this with the reader's expansion)."
  [tag body]
  (let [sdef (structure-by-tag tag)]
    (when-not sdef
      (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
    (if (and (= 1 (count body)) (map? (first body)))
      (build-instance-form tag nil nil true
                           (map->clauses tag sdef (apply-syntax sdef (first body))))
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
                            (and (empty? (rest cls)) (or (map? one) (:syntax sdef))))
                (throw (ex-info (str (clojure.core/name tag) " " sym ": an instance is `("
                                     (clojure.core/name tag)
                                     " name \"doc\"? {slot → value}? nested…)` — got "
                                     (pr-str (vec cls)))
                                {:tag tag :sym sym :body (vec cls)})))
        m     (apply-syntax sdef (if (some? one) one {}))
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

(defn value-literal->iv
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
           (let [ttag (:target sl)]
             (when-not (:reader (structure-by-tag ttag))
               (throw (ex-info (str "cannot reify type form " (pr-str literal) " — slot target "
                                    ttag " has no reader (named-Kind refs are not reflectable)")
                               {:tag tag :literal literal})))
             (update iv :clauses conj {:rk (:rel sl) :card (:card sl)
                                       :targets (mapv #(value-literal->iv ttag %) args)})))))
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
;; with negation routed through RULES, never inline not-join, so datascript's
;; wholly-empty-relation gotcha is encapsulated here, once. The authored form is
;; kept on the law as :src (the print-dual renders it back).

(defn- when-clauses
  "{:polarity \"code-up\"} → scalar-equality clauses on `var`."
  [var when-map]
  (mapv (fn [[k v]] [var (keyword "val" (clojure.core/name k)) v]) when-map))

(defn- combinator-law
  "Expand `(law \"desc\" (combinator …))` into a parsed law map:

     (has R :when {k v}?)        every instance (satisfying :when) has ≥1 outgoing R
     (has-any R1 R2 …)           … has at least one of the Rs
     (matched-by R :from S? :when {k v}? :scope T?)
                                 every instance is the TARGET of some R (from an S)
     (target R {k v})            every R-target satisfies the value conditions
     (at-most-one R)             at most one incoming R (a unique owner/matcher)

   :when filters the law's subjects by scalar values; :from constrains the
   matching counterpart's structure; :scope (a structure symbol) hosts the law
   about ANOTHER structure's instances (default: self-scoped to the owner)."
  [desc form]
  (let [[op & args] form
        kvs    (fn [xs] (apply hash-map xs))
        merged (fn [scope m] (merge {:desc desc :src form
                                     :scope (when scope (resolve-struct-tag scope))} m))]
    (case op
      has
      (let [[rel & opts] args
            {whenm :when scope :scope} (kvs opts)]
        (merged scope
                {:offenders '[?x]
                 :rules [[(list 'law-has '?x) ['?r :rel/from '?x] ['?r :rel/kind rel]]]
                 :where (conj (when-clauses '?x whenm) '(not (law-has ?x)))}))
      has-any
      (merged nil
              {:offenders '[?x]
               :rules (mapv (fn [rel] [(list 'law-has '?x) ['?r :rel/from '?x] ['?r :rel/kind rel]])
                            args)
               :where ['(not (law-has ?x))]})
      matched-by
      (let [[rel & opts] args
            {whenm :when scope :scope from :from} (kvs opts)]
        (merged scope
                {:offenders '[?x]
                 :rules [(vec (concat [(list 'law-matched '?x)]
                                      (when from [['?c :structure/of (resolve-struct-tag from)]])
                                      [['?r :rel/from '?c] ['?r :rel/kind rel] ['?r :rel/to '?x]]))]
                 :where (conj (when-clauses '?x whenm) '(not (law-matched ?x)))}))
      target
      (let [[rel whenm] args]
        (merged nil
                {:offenders '[?x]
                 :rules [(vec (cons (list 'law-target-ok '?t) (when-clauses '?t whenm)))]
                 :where [['?r :rel/from '?x] ['?r :rel/kind rel] ['?r :rel/to '?t]
                         '(not (law-target-ok ?t))]}))
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
      (do (when (next kvs)
            (throw (ex-info (str "a combinator law takes one form: " (pr-str form)) {:form form})))
          (combinator-law desc (first kvs)))
      (let [m (apply hash-map kvs)]
        {:desc desc
         :offenders (unquote-lit (:offenders m))
         :where     (unquote-lit (:where m))
         :rules     (unquote-lit (:rules m))
         :scope     (:scope m)}))))

(defn- defined-rule-names
  "The set of rule-head names defined in a datalog rule vector."
  [rules]
  (set (map (comp first first) rules)))

(defn- rule-refs
  "Defined rule-names invoked as clause heads within `clauses`, recursing into
   not / not-join / or / and wrappers. Datom vectors `[?e :a ?v]` and predicate
   clauses `[(pred …)]` are not rule calls, so they're skipped."
  [clauses defined]
  (mapcat
   (fn [c]
     (if (and (seq? c) (symbol? (first c)))
       (let [h (first c)]
         (cond
           (#{'not 'or 'and} h)  (rule-refs (rest c) defined)
           (= 'not-join h)       (rule-refs (drop 2 c) defined)
           (contains? defined h) [h]
           :else                 []))
       []))
   clauses))

(defn- check-law-recursion!
  "Reject a law whose :rules contains a self-recursive rule that ALSO calls
   another rule. datascript diverges on cyclic data for that rule-calls-rule
   shape (see the no-cycle / reachability finding) — the helper rule's clauses
   must be inlined into the recursive rule. The *law-timeout-ms* guard is the
   runtime backstop for divergent shapes this static check misses (e.g. mutual
   recursion)."
  [sname {:keys [desc rules]}]
  (when (seq rules)
    (let [defined (defined-rule-names rules)]
      (doseq [rule rules]
        (let [hname (-> rule first first)
              refs  (set (rule-refs (rest rule) defined))]
          (when (and (contains? refs hname) (seq (disj refs hname)))
            (throw (ex-info
                    (str "defstructure " sname ", law " (pr-str desc)
                         ": recursive rule (" hname ") calls another rule "
                         (vec (disj refs hname)) " — datascript diverges on cyclic data "
                         "for rule-calls-rule recursion. Inline that rule's clauses "
                         "directly into (" hname ").")
                    {:structure sname :law desc :rule hname}))))))))

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
   (includes ...) / (realized-as ...); anything else is rejected at macro-expansion
   time (a silently-dropped form is a footgun).

   A law's recursive :rules must INLINE their step — a self-recursive rule may
   not call another rule, because datascript diverges on cyclic data for that
   shape (rejected here; the *law-timeout-ms* guard backstops the rest)."
  [sname docstring & body]
  (doseq [form body]
    (when-not (or (map? form)
                  (and (seq? form) (#{'law 'reader 'syntax 'includes 'realized-as} (first form))))
      (throw (ex-info (str "defstructure " sname ": unknown body form " (pr-str form)
                           " — expected a slots map, (law ...), (reader ...), (syntax ...), (includes ...) or (realized-as ...)")
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
        _      (doseq [law laws] (check-law-recursion! sname law))
        reader-form (some (fn [f] (when (= 'reader (first f)) (second f)))
                          (filter #(= 'reader (first %)) body))
        ;; an instance-level authoring-syntax fn (the reader's analogue, raised from a single
        ;; slot's literal to the whole instance arg-tail): applied to the body before clause
        ;; parsing, so a structure owns its surface sugar (e.g. Operation's `->`) — NOT core.
        syntax-form (some (fn [f] (when (= 'syntax (first f)) (second f)))
                          (filter #(= 'syntax (first %)) body))
        includes (->> body (filter #(= 'includes (first %)))
                      (mapcat rest) (mapv resolve-struct-tag))
        realized (some (fn [f] (when (= 'realized-as (first f)) (unquote-lit (second f))))
                       (filter #(= 'realized-as (first %)) body))
        _      (when realized
                 (when (or (seq slots) (seq laws) value? (seq includes) reader-form)
                   (throw (ex-info (str "defstructure " sname
                                        ": a realized concept (realized-as) is pure derived membership —"
                                        " it may not also declare slots, laws, includes, a reader, or ^:value")
                                   {:structure sname})))
                 (when (> (count (filter #(and (seq? %) (= 'realized-as (first %))) body)) 1)
                   (throw (ex-info (str "defstructure " sname ": multiple (realized-as …) forms")
                                   {:structure sname}))))
        sdef   {:tag tag :doc docstring :slots slots :laws laws :value? value?
                :includes includes :realized-as realized}]
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
   `:relation-coproduct`; `derive-rules` emits the union rules so laws/lenses can read
   the umbrella relation `V` at domain altitude. It is the relation-level analogue of a
   `realized-as` coproduct (one level up, over `:rel/kind` instead of node kinds): it has
   no slots, laws, constructor, or instances. Members must be live relation kinds — i.e.
   relation slots present somewhere in the loaded vocab — else the union rule references an
   undefined rule.

     (defrelation-coproduct :view-map \"cross-view mapping\" :via :contextualizes)"
  [rtag docstring & members]
  (let [tag        (keyword (name rtag))
        member-kws (mapv (comp keyword name) members)]
    `(register-structure! {:tag ~tag :doc ~docstring :slots [] :laws [] :includes []
                           :relation-coproduct ~member-kws})))

(defmacro defrelation
  "Declare a DERIVED RELATION — a named datalog rule with a CUSTOM body, injected into
   every law and every `vocab-rules` query at domain altitude (by `check`, exactly as the
   vocab-derived kind/relation rules are). It is the custom-body generalization of a slot's
   relation rule and of `realized-as` (derived UNARY membership), and the open-bodied sibling
   of `defrelation-coproduct` (a relation that is a UNION of existing relation kinds): it has
   no slots, laws, constructor, or instances — only the rule. So a join several laws would
   each re-inline (the model↔code op-twin, say) is expressed ONCE here, and the laws just
   call it `(op-twin ?a ?b)` instead of repeating the clauses.

   `head` is the rule's argument vector; `where` its body clauses, which may reference other
   injected rules (`in-module`, `named`, …) and call predicates. Keep `where` non-recursive:
   vocab-injected rules run outside the per-law *law-timeout-ms* guard.

     (defrelation :op-twin \"an authored op ?a and its extracted code twin ?b\"
       '[?a ?b]
       '[[?a :structure/of :canvas.vocab.code.operation/Operation] (not [?a :val/extracted true]) [?a :entity/name ?n]
         (in-module ?a ?cm)
         [?b :structure/of :canvas.vocab.code.operation/Operation] [?b :val/extracted true] [?b :entity/name ?n]
         (in-module ?b ?km)
         [(canvas.vocab.code.module/module-corresponds? ?cm ?km)]])"
  [rtag docstring head where]
  (let [tag (keyword (name rtag))]
    `(register-structure! {:tag ~tag :doc ~docstring :slots [] :laws [] :includes []
                           :derived-rule {:head '~(unquote-lit head) :where '~(unquote-lit where)}})))

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

(defn- slot-laws
  "Derive laws for a structure's slots, dispatching value vs relation slots."
  [{:keys [tag slots]}]
  (mapcat #(if (scalar-slot? %)
             (value-slot-laws tag %)
             (relation-slot-laws tag %))
          slots))

(defn laws-of
  "Every law of structure `sdef` — the slot-derived cardinality/type laws plus its
   free `(law …)`s, the same set `check` runs. Public so an alternative engine (the
   Cozo law compiler) can evaluate the identical laws."
  [sdef]
  (concat (slot-laws sdef) (:laws sdef)))

(defn- law-scope-tag
  "The structure tag a free law's first offender var is scoped to: its :scope
   when set (nil when :scope is :global), else its owning structure. Slot-derived
   laws self-scope and carry no :owner, so they get no injection."
  [{:keys [scope owner]}]
  (case scope
    :global nil
    nil     owner
    scope))

(defn direct-scope-tags
  "Qualified tags whose instances carry `:structure/of` DIRECTLY, so a law scoped to one can be
   pinned ns-precisely (`[?o :structure/of tag]`) instead of riding the short-name rule. Excludes
   facets (`includes` targets — their members are reached via the inclusion rule, not a direct tag)
   and realized/coproduct concepts (no instances). For these direct tags two same-short-named
   structures from different namespaces never cross-scope."
  [structures]
  (let [facets (into #{} (mapcat :includes) structures)]
    (into #{}
          (comp (remove #(or (:realized-as %) (:relation-coproduct %) (:derived-rule %)))
                (map :tag)
                (remove facets))
          structures)))

(def ^:dynamic *law-timeout-ms*
  "Per-law wall-clock budget for `check`. A recursive law that exceeds it —
   e.g. unbounded recursion over a cyclic/indirect graph — is reported as
   timed-out instead of hanging the whole check. Best-effort: the underlying
   datascript query thread is abandoned (datascript queries aren't cleanly
   interruptible), so it runs on until it finishes or the JVM exits."
  5000)

(defn vocab-rules
  "The datascript rules derived from the live vocabulary (one per kind + per relation
   slot, plus the fixed substrate rules). Lets queries — and laws (via `check`) — read
   at domain altitude: `(Operation ?s) (in-module ?s \"…\") (calls ?s ?c)`."
  []
  (rules/derive-rules (all-structures) scalar-slot?))

(defn- rules-recursive?
  "Whether a law's own rules can recurse: some rule's body invokes a rule the law
   itself defines (self- or mutual). Vocab-derived rules can't call a law's rules,
   so only these need the timeout guard; plain helper rules (e.g. combinator
   expansions) run on the fast path."
  [rules]
  (let [defined (defined-rule-names rules)]
    (boolean (some #(seq (rule-refs (rest %) defined)) rules))))

(defn- run-query
  "Run a law's offender query, returning the offender rows (or nil if none).
   Only laws with their OWN recursive `:rules` can diverge, so only they are run under
   the *law-timeout-ms* guard (via a future); non-recursive laws run inline. Either
   way the query gets `rules` (the vocab-derived rules merged with the law's own).
   Returns ::timeout if a guarded query exceeds the budget."
  [db q rules recursive?]
  (if recursive?
    (let [fut     (future (d/q q db rules))
          results (deref fut *law-timeout-ms* ::timeout)]
      (if (= results ::timeout)
        (do (future-cancel fut) ::timeout)
        results))
    (d/q q db rules)))

(defn- run-law [db base-rules direct-tags {:keys [offenders where rules] :as law}]
  ;; Scope the first offender var to the law's structure. A DIRECTLY-instantiated concrete tag is
  ;; pinned ns-precisely via `[?o :structure/of tag]` — so two same-short-named structures from
  ;; different namespaces (e.g. a concept re-stated at two altitudes) never cross-scope. Facets
  ;; (`includes` targets) and realized/derived concepts have no direct `:structure/of`, so they ride
  ;; the short-name RULE `(Foo ?o)`, which chains the inclusion / realized-as rules to reach their
  ;; members. (A same-short-name collision survives only for those rarely-co-loaded abstract tags.)
  (let [scope-tag    (law-scope-tag law)
        scope-clause (when scope-tag
                       (if (contains? direct-tags scope-tag)
                         [(first offenders) :structure/of scope-tag]
                         (list (symbol (name scope-tag)) (first offenders))))
        where*       (if scope-clause (vec (cons scope-clause where)) where)
        q            (vec (concat [:find] offenders [:in '$ '%] [:where] where*))
        ;; vocab-derived rules are always available; only actually-recursive own
        ;; rules need the timeout guard
        results   (run-query db q (into (vec base-rules) rules) (rules-recursive? rules))]
    (cond
      (= results ::timeout) ::timeout
      (seq results)         (mapv vec results)
      :else                 nil)))

(defn check
  "Run every registered structure's laws (slot-cardinality + free) over `db`, with the
   vocab-derived rules injected so law `:where`s can read at domain altitude.
   Returns a vector of result maps:
     {:structure :law :offenders [...]}        — a violation
     {:structure :law :timed-out? true :message …} — a (recursive) law that
        exceeded *law-timeout-ms* instead of completing."
  [db]
  (let [base   (vocab-rules)
        direct (direct-scope-tags (all-structures))]
   (vec
    (for [{:keys [tag laws] :as sdef} (all-structures)
          {:keys [desc] :as law} (concat (slot-laws sdef) laws)
          :let [r (run-law db base direct law)]
          :when r]
     (if (= r ::timeout)
       {:structure tag :law desc :timed-out? true
        :message (str "law exceeded *law-timeout-ms* (" *law-timeout-ms* "ms) "
                      "without completing — likely unbounded recursion over a "
                      "cyclic/indirect graph (recursion must run over a direct "
                      "binary relation, not a rule-derived one)")}
       {:structure tag :law desc :offenders r})))))
