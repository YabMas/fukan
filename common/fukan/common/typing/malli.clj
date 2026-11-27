(ns fukan.common.typing.malli
  "The malli type DIALECT — fukan's realization of the kernel's `typing` plug-point. A self-contained
   PLUGIN in its own area (`fukan.common.typing/`), NOT a general-vocab primitive: the whole dialect is malli,
   top to bottom, so it lives under the malli name. It owns a SPECIALIZED vocabulary (malli's grammar
   modelled as content-deduped `^:value` structures — `Schema`/`SchemaChoice`/`SchemaField`) plus the
   runtime bridges that interpret it (`render`/`valid?`), together, because both are specific to malli.
   The code vocab (Kind shapes, Operation signatures) and grammar reflection consume it.

   This is the HOOK side of the typing SPI — the plug-point + bridge SHAPE stay in
   `fukan.canvas.core.typing`; requiring this namespace self-registers the full dialect (its bridges +
   its value-structure tag), so a model carries its type checking, reflection, and rendering by opting
   in. The core stays blind — it sees an opaque schema reference; this dialect owns ALL interpretation.

   Three sections below: the shape vocabulary (malli-as-data), the runtime bridges (the interpretation
   mechanism), and the dialect wiring (registration into the SPI)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]))

;; ── the shape vocabulary (malli's grammar as queryable ^:value structures) ─────

;; THE SURFACE. Malli's whole vocabulary, by the SHAPE of what follows the head — which is how
;; malli itself groups its schemas (leaf types, schemas over child schemas, ENTRY schemas whose
;; children are keyed, schemas over literal values) and the only thing the reader, the renderer
;; and the walking relations need to know about an op. One table, so covering a schema is one
;; line here and nothing anywhere else; `covers?` reads it, and a test enumerates malli's
;; registries against it, so a builtin this table does not hold FAILS THE BUILD instead of
;; quietly reading as a reference to a type nobody modelled (which is what `:qualified-keyword`
;; did until 2026-11-19).
(def ^:private op-shapes
  "Malli schema type (the keyword a form leads with) → the shape of its children.

     :leaf        none                      `:int`, `[:int {:min 0}]`
     :one         exactly one schema        `[:vector X]`, `[:maybe X]`, `[:* X]`, `[:not X]`
     :many        schemas, in order         `[:or A B]`, `[:tuple A B]`, `[:cat A B]`
     :fields      keyed entries, unordered  `[:map [:k V] …]`
     :entries     keyed entries, in order   `[:orn [:k V] …]`, `[:multi {:dispatch f} [v V] …]`
     :values      literal values            `[:enum :a 1 nil]`, `[:> 5]`, `[:fn (fn [x] …)]`
     :re          one regex                 `[:re \"pat\"]`
     :ref         one NAME                  `[:ref :order/id]`
     :arrow       input, output, guard?     `[:=> [:cat A] B]`
     :flat-arrow  inputs…, output           `[:-> A B]`
     :select      a schema and a key list   `[:select-keys M [:a :b]]`"
  {;; malli.core/type-schemas
   :any :leaf, :some :leaf, :nil :leaf, :string :leaf, :int :leaf, :float :leaf, :double :leaf,
   :boolean :leaf, :keyword :leaf, :symbol :leaf, :qualified-keyword :leaf,
   :qualified-symbol :leaf, :uuid :leaf,
   ;; malli.core/comparator-schemas — one literal each
   :> :values, :>= :values, :< :values, :<= :values, := :values, :not= :values,
   ;; malli.core/sequence-schemas
   :* :one, :+ :one, :? :one, :repeat :one, :cat :many, :alt :many, :catn :entries, :altn :entries,
   ;; malli.core/base-schemas (`:andn` arrived in malli 0.18 and is read on any version)
   :and :many, :or :many, :orn :entries, :andn :entries, :not :one,
   :map :fields, :map-of :many, :vector :one, :sequential :one, :set :one, :seqable :one,
   :every :one, :tuple :many, :maybe :one, :multi :entries, :enum :values,
   :re :re, :fn :values, :ref :ref, :schema :one, :malli.core/schema :one,
   :=> :arrow, :-> :flat-arrow, :function :many,
   ;; malli.util/schemas — opt-in in malli, covered here: they are spelled as data like the rest
   :merge :many, :union :many, :select-keys :select})

(def ^:private exact-arity
  "Ops among the :many/:values shapes that take an exact number of children."
  {:map-of 2, :> 1, :>= 1, :< 1, :<= 1, := 1, :not= 1, :fn 1})

(def ^:private predicate-names
  "Malli's PREDICATE schemas (`int?`, `string?`, `pos-int?`, …) by name. Taken from the library
   rather than restated: every one of them is a leaf by construction (`malli.core` builds each
   from a var and nothing else), so a predicate malli gains is covered the day it does."
  (into #{} (comp (filter symbol?) (map name)) (keys (m/predicate-schemas))))

(def ^:private predicate-fns
  "The same predicates as FUNCTION OBJECTS → their symbol. Malli registers each under both; a
   schema read from source only ever holds the symbol, and one built at runtime may hold the fn."
  (into {} (keep (fn [n] (when-let [v (resolve (symbol "clojure.core" n))] [@v (symbol n)])))
        predicate-names))

(defn covers?
  "Does the dialect read the schema malli registers under `k`? `k` is a registry KEY as
   `(keys (malli.core/default-schemas))` gives them: a keyword type, a predicate symbol, the
   same predicate as a function object, or the regex class."
  [k]
  (cond
    (keyword? k) (contains? op-shapes k)
    (symbol? k)  (contains? predicate-names (name k))
    (fn? k)      (contains? predicate-fns k)
    :else        (= java.util.regex.Pattern k)))

;; WHAT IS NOT A SCHEMA CHILD. The dialect reads a schema's STRUCTURE — its type, its child
;; schemas, its keys — and decomposes that into nodes a law can query. Everything else a malli
;; form may carry is stored OPAQUELY BUT FAITHFULLY, as its printed text in one leaf: a
;; property map (`:title`, `:closed`, `:dispatch`, `:gen/*`, a local `:registry`), a literal
;; value (an enum member, a comparator's bound), a `[:fn (fn [x] …)]` body. Nothing looks
;; inside it, it is part of the schema's identity, and it renders back as it was written.
;;
;; That holds for whatever is DATA, and a schema read from source is data all the way down —
;; a `(fn …)` there is a list. What is not data has no text to store: a function OBJECT, a
;; class, an expression that computes a schema (`(mu/dissoc Claim :held)`). Those are REFUSED
;; (an ex-info), never guessed and never dropped: the extractor marks such a function
;; `:signature-unread`, and an authored instance fails where it is written. Two objects have a
;; faithful spelling and get it — a regex its pattern string, a registered predicate fn its
;; symbol.

(def ^:private text-order (fn [a b] (compare (pr-str a) (pr-str b))))

(defn- canonical
  "`x` as plain data in ONE spelling (map and set entries ordered), or a throw where it holds
   something that is not data."
  [x]
  (cond
    (map? x)    (into (sorted-map-by text-order) (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x)    (into (sorted-set-by text-order) (map canonical) x)
    (vector? x) (mapv canonical x)
    (seq? x)    (apply list (map canonical x))
    (or (nil? x) (boolean? x) (number? x) (string? x) (char? x) (keyword? x) (symbol? x)
        (uuid? x) (inst? x) (instance? java.util.regex.Pattern x))
    x
    :else (throw (ex-info (str "not data, so not storable as part of a schema: " (pr-str x)
                               " (" (.getName (class x)) ") — write the schema as a literal form")
                          {:value x}))))

(defn- text
  "The opaque-but-faithful spelling of a piece of data: its canonical printed text."
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-namespace-maps* false *print-readably* true]
    (pr-str (canonical x))))

(defn- untext
  "The data a `text` leaf spells."
  [s]
  (binding [*read-eval* false] (read-string s)))

(defn- plain-name?
  "Is `s` text that reads back as one plain symbol of that exact spelling?"
  [s]
  (let [r (try (untext s) (catch Exception _ nil))]
    (and (symbol? r) (= s (str r)) (not (str/starts-with? s "'")))))

(defn- key->label
  "An entry KEY as the string stored for it. A simple keyword — nearly every key there is —
   is its bare name (`:street` → \"street\"), which is what a query for a field by name writes.
   Any other key keeps its type in its spelling: a qualified keyword its colon (\":order/id\"),
   a symbol a quote (\"'k\"), a string, a number, `true` their printed text."
  [k]
  (cond
    (and (simple-keyword? k) (plain-name? (name k))) (name k)
    (symbol? k) (str "'" k)
    :else (text k)))

(defn- label->key
  "The entry key a stored label spells — `key->label`'s inverse."
  [s]
  (cond
    (str/starts-with? s "'") (symbol (subs s 1))
    (plain-name? s)          (keyword s)
    :else                    (untext s)))

(defn- type-name
  "A keyword's WHOLE name, namespace included, without the colon."
  [k]
  (subs (str k) 1))

(defn- props+args
  "Split what follows a form's head into `[properties children]`. Malli's own rule: the first
   element is the property map when it is a map OR nil — so `[:enum nil 1]` is an enum of `1`."
  [more]
  (if (and (seq more) (or (nil? (first more)) (map? (first more))))
    [(not-empty (first more)) (rest more)]
    [nil more]))

(defn- pattern-text
  "The pattern a regex is written with, or nil: a regex object's, or the one in the form
   `(re-pattern \"…\")` — which is how a `#\"…\"` literal arrives from a reader that hands back
   data only (clj-kondo's)."
  [x]
  (cond
    (instance? java.util.regex.Pattern x) (str x)
    (and (seq? x) (= 're-pattern (first x)) (= 2 (count x)) (string? (second x))) (second x)))

(defn- child
  "A child schema as the form handed on to be read. The spellings rewritten here are the ones
   the instance machinery would otherwise take for something else: a SYMBOL-headed vector
   (`[int? {:min 1}]`, a predicate carrying properties) is its `[label target]` syntax, so it
   is passed as the keyword-headed form `read-malli` reads alike; and a LIST is code to it.
   `(var X)` is the name `X` and `(re-pattern \"…\")` the regex it spells; any other list is an
   expression — a computed schema — which a static read does not take."
  [x]
  (cond
    (and (vector? x) (symbol? (first x))) (into [(keyword (name (first x)))] (rest x))
    (and (seq? x) (= 'var (first x)) (symbol? (second x))) (second x)
    (pattern-text x) [:re (pattern-text x)]
    (seq? x) (throw (ex-info (str "a schema is read as data, and this is an expression: "
                                  (pr-str x) " — write the schema it computes, or name it")
                             {:form x}))
    :else x))

(defn- literal
  "A literal VALUE as the form handed to SchemaChoice's reader: a keyword, string or symbol
   as itself; anything else wrapped as its text."
  [v]
  (if (or (keyword? v) (string? v) (symbol? v)) v {:edn (text v)}))

(defn ^:export read-choice
  "A literal value → SchemaChoice clauses. The value's TYPE is preserved as :kind, so
   `[:enum :a]` and `[:enum \"a\"]` are distinct values and forms round-trip exactly. A keyword,
   string or symbol arrives RAW; any other value (a number, nil, a boolean, a collection, a
   code form) arrives as `{:edn text}` and is kind \"edn\"."
  [m]
  (cond
    (keyword? m) [(list 'value (type-name m)) (list 'kind "keyword")]
    (string? m)  [(list 'value m) (list 'kind "string")]
    (symbol? m)  [(list 'value (str m)) (list 'kind "symbol")]
    (and (map? m) (= [:edn] (keys m)) (string? (:edn m)))
    [(list 'value (:edn m)) (list 'kind "edn")]
    :else (throw (ex-info (str "a literal value is a keyword, string or symbol, or {:edn text}: "
                               (pr-str m)) {:member m}))))

(defstructure ^:value SchemaChoice
  "One LITERAL VALUE a schema is written over — an enum member, a comparator's bound
   (`[:> 5]`), a `[:fn …]` body, a selected key — value-identified by (text, kind): `:red` is
   one node shared across enums, distinct from `\"red\"`. Kind \"edn\" is any value that is not
   a keyword, string or symbol, held as its printed text."
  {:value :string
   :kind  [:enum "keyword" "string" "symbol" "edn"]}
  (reader read-choice))

(defn ^:export read-field
  "A keyed entry `[key-label schema-form optional? properties-text]` -> SchemaField clauses.
   The schema-form is re-expanded by the interpreter (SchemaField's :schema targets Schema,
   which carries read-malli)."
  [[k v opt p]]
  (cond-> [(list 'key k) (list 'schema v) (list 'optional (boolean opt))]
    p (conj (list 'props p))))

(defstructure ^:value SchemaField
  "A keyed entry of an ENTRY schema — a map's field, an `:orn`/`:altn` branch, a `:catn`
   element, a `:multi` case: its key, its Schema, and what its property map said. `:optional`
   is the one property read; the rest ride `:props` as text."
  {:key      :string            ; the entry key (see `key->label`)
   :schema   Schema
   :optional :boolean
   :props    [:? :string]}      ; the entry's other properties, opaque
  (reader read-field))

(defn- props-clauses
  "A property map → clauses. `:min`/`:max` are leaves a law can compare when they are
   integers (as is `:re` on a `:string`, fukan's own spelling of a pattern constraint);
   whatever else the map holds — and a bound that is not an integer — is the opaque `:props`."
  [kind props]
  (let [leaf? (fn [k] (int? (get props k)))
        re    (when (= kind "string") (:re props))
        rest* (cond-> props
                (leaf? :min) (dissoc :min)
                (leaf? :max) (dissoc :max)
                re           (dissoc :re))]
    (cond-> []
      (leaf? :min) (conj (list 'min (:min props)))
      (leaf? :max) (conj (list 'max (:max props)))
      re           (conj (list 'regex (or (pattern-text re) (str re))))
      (seq rest*)  (conj (list 'props (text rest*))))))

(defn- entry-parts
  "One entry of an entry schema → `[key properties schema-form]`. Malli's spellings:
   `[k schema]`, `[k props schema]`, and — the key naming a registered schema — `[k props]`
   or the bare `k`, where the entry's schema is the reference of that name."
  [e]
  (if (vector? e)
    (let [[k & more] e
          [props args] (props+args more)]
      [k props (if (seq args) (first args) k)])
    [e nil e]))

(defn- entry-clause
  "An entry → the `field`/`entry` clause carrying it to SchemaField's reader."
  [slot e]
  (let [[k props schema] (entry-parts e)
        other (not-empty (dissoc props :optional))]
    (list slot [(key->label k) (child schema) (true? (:optional props)) (when other (text other))])))

(defn- arrow-params
  "A function schema's INPUT → ordered `[param-name-or-nil type-form]` pairs. `[:cat T …]`
   gives positional parameters, `[:catn [:name T] …]` named ones, the bare `:cat` none. Any
   other input — a `:cat`/`:catn` carrying properties, or properties on an entry — is ONE
   positional parameter holding that whole schema, which is what malli takes it to mean: a
   sequence schema inside a `:cat` is spliced into it.

   A NAME is design documentation, and it is what the print-dual renders back; it is
   deliberately NOT part of what adherence compares (`arrow-differs` below ignores it), so a
   project may name its design parameters and leave its annotations positional."
  [input]
  (let [[op & more] (when (vector? input) input)
        [props args] (props+args more)
        named? (fn [e] (and (vector? e) (= 2 (count e)) (simple-keyword? (first e))))]
    (cond
      (= :cat input)                              []
      (and (= :cat op) (nil? props))              (mapv (fn [t] [nil t]) args)
      (and (= :catn op) (nil? props) (every? named? args))
      (mapv (fn [[k t]] [(symbol (name k)) t]) args)
      :else                                       [[nil input]])))

(defn- in-clauses
  "Arrow parameters → `in` clauses. A NAMED param is a `[label target]` clause (the name rides
   the edge as `:rel/label`); an unnamed one is the bare target. The name is therefore in the
   arrow's value identity — which is why `arrow-differs` compares positions rather than node
   identity."
  [params]
  (map (fn [[pname ptype]] (list 'in (if pname [pname (child ptype)] (child ptype)))) params))

(defn- localize
  "Rewrite `form` so that every reference to a name in `locals` — the keys of a local
   `:registry` in scope — is the internal `[::local name]` form. Walks SCHEMA positions only
   (the table says which those are), so a key, an enum member or a property that happens to
   spell a local name is left alone."
  [locals form]
  (cond
    (contains? locals form) [::local form]
    (and (vector? form) (keyword? (first form)))
    (let [[op & more] form
          [props args] (props+args more)
          re    (fn [x] (localize locals x))
          entry (fn [e]
                  (let [[k p schema] (entry-parts e)]
                    (if (or (vector? e) (contains? locals e))
                      (cond-> [k] p (conj p) true (conj (re schema)))
                      e)))
          head  (if props [op props] [op])]
      (case (op-shapes op)
        (:one :many :arrow :flat-arrow) (into head (map re args))
        (:fields :entries)              (into head (map entry args))
        :select                         (into head (cons (re (first args)) (rest args)))
        :ref                            (if (contains? locals (first args))
                                          (into (if props [::local props] [::local]) args)
                                          form)
        form))
    :else form))

(defn- reference
  "The clauses of a REFERENCE to the type named `nm`."
  [nm props]
  (into [(list 'kind "ref") (list 'ref nm)] (props-clauses "ref" props)))

(defn ^:export read-malli
  "Expand a native malli data-literal into Schema construction clauses (one level). It reads
   the whole of malli's vocabulary — `op-shapes` is the list, by shape:

     :int :string :uuid :qualified-keyword …   a type schema — a leaf
     int? string? pos-int? …                   a predicate schema — a leaf, kept as the symbol
     [:int {:min _ :max _}] [int? {…}]         either, with properties
     [:vector|:set|:sequential|:seqable|:every X]   a collection of element X
     [:maybe X] [:not X] [:schema X]           one child
     [:*|:+|:?|:repeat X]                      malli regex repetition — how a VARARGS
                                               parameter is typed inside [:cat …]
     [:tuple A B …] [:or …] [:and …] [:cat …] [:alt …] [:merge …] [:union …]
                                               ordered children :of
     [:map-of K V]                             homogeneous map — key schema, value schema
     [:map [:k V] [:k {:optional true} V]]     keyed :field entries (unordered)
     [:orn [:k V] …] [:altn …] [:catn …] [:andn …] [:multi {:dispatch f} [v V] …]
                                               keyed :entry entries (ordered)
     [:enum :a \"b\" 1 nil]                      :choice members, type preserved
     [:> 5] [:>= 0] [:= :x] [:not= :x] …       a comparator — its bound as a :choice
     [:fn (fn [x] …)]                          a predicate — its body as a :choice, opaque
     [:re \"pat\"] / #\"pat\"                      a regex
     [:=> [:cat A B] Out] / [:=> … Out Guard]  function type — ordered :in params + :out
     [:=> [:catn [:name T] …] Out]             the same, parameters named
     [:-> A B Out]                             the same, malli's flat spelling
     [:function [:=> …] [:=> …]]               a MULTI-ARITY function — one arrow per arity
     [:select-keys M [:a :b]]                  a schema and the keys kept of it
     Foo  (a bare symbol)                      a `ref` schema NAMING the type `Foo` —
     :Foo (a non-builtin bare keyword)         the referenced type name in a `:ref`
     \"Foo\" / [:ref \"Foo\"]                     leaf (no edge; resolution to what it names
                                               is the by-name `names-type` relation)
     :order/id (a QUALIFIED keyword)           a `ref` too, by its WHOLE name
                                               (`order/id`) — the namespace is part of
                                               what the keyword names, and a domain
                                               attribute is told from another by it

   THE KEYWORD PARTITION. An unqualified keyword malli knows (`op-shapes`) is that schema; any
   other bare keyword, a bare symbol that is no predicate, or a string is a name-ref — all
   reduce to the same `{:kind \"ref\", :ref \"<Name>\"}` (malli's model: a keyword is a registry
   lookup, builtin vs a domain name). A QUALIFIED keyword is ALWAYS a reference, by its whole
   name: every type malli registers by default is unqualified, bar the internal
   `:malli.core/schema`, which is only ever a form's head. That includes the opt-in
   `malli.experimental.time` types (`:time/instant`, …): they are qualified, the namespace is
   the domain's to use, and a project that registers them models them as named types.

   PROPERTIES ride every form in malli's position and are kept (see `props-clauses`). A local
   `{:registry {…}}` is kept with them, opaque; a reference to a name it binds is a LOCAL
   reference (kind \"local\") — it names nothing in the model, so it is not held to resolve.

   What is not data is refused, not guessed (see WHAT IS NOT A SCHEMA CHILD above)."
  [data]
  (cond
    (keyword? data) (cond
                      (namespace data) (reference (type-name data) nil)
                      (op-shapes data) (read-malli [data])
                      :else            (reference (name data) nil))
    (symbol? data)  (if (and (contains? predicate-names (name data))
                             (contains? #{nil "clojure.core"} (namespace data)))
                      [(list 'kind (name data))]
                      (reference (name data) nil))
    (string? data)  (reference data nil)
    (pattern-text data) [(list 'kind "re") (list 'regex (pattern-text data))]
    (contains? predicate-fns data) (read-malli (predicate-fns data))
    (and (vector? data) (symbol? (first data)))
    (if (and (= 2 (count data)) (map? (second data)) (contains? predicate-names (name (first data))))
      (read-malli (child data))
      (throw (ex-info (str "a malli schema vector needs a keyword head — write [:vector X], not " (pr-str data))
                      {:form data})))
    (vector? data)
    (let [op (first data)
          _  (when-not (keyword? op)
               (throw (ex-info (str "a malli schema vector needs a keyword head — write [:vector X], not " (pr-str data))
                               {:form data})))
          registry (let [[props _] (props+args (rest data))] (:registry props))
          [_ & more] (if (map? registry) (localize (set (keys registry)) data) data)
          [props args] (props+args more)
          shape (op-shapes op)
          kind  (type-name op)
          node  (fn [& clauses] (-> [(list 'kind kind)] (into (props-clauses kind props)) (into clauses)))
          arity (fn [n]
                  (when-not (= n (count args))
                    (throw (ex-info (str "malli's " op " takes " n " child" (when (not= 1 n) "ren")
                                         ", got " (count args) ": " (pr-str data)) {:form data}))))]
      (when-let [n (exact-arity op)] (arity n))
      (case shape
        :leaf    (do (arity 0) (node))
        :one     (do (arity 1) (node (list 'of (child (first args)))))
        :many    (node (cons 'of (map child args)))
        :fields  (apply node (map #(entry-clause 'field %) args))
        :entries (apply node (map #(entry-clause 'entry %) args))
        :values  (apply node (map #(list 'choice (literal %)) args))
        :select  (do (arity 2)
                     (when-not (sequential? (second args))
                       (throw (ex-info (str ":select-keys takes a schema and a vector of keys: " (pr-str data))
                                       {:form data})))
                     (apply node (list 'of (child (first args)))
                            (map #(list 'choice (literal %)) (second args))))
        :re      (do (arity 1)
                     (let [pattern (if (string? (first args)) (first args) (pattern-text (first args)))]
                       (when-not pattern
                         (throw (ex-info (str ":re takes a pattern, as a string or a regex: " (pr-str data))
                                         {:form data})))
                       (node (list 'regex pattern))))
        ;; `[:ref X]` is malli's LAZY spelling of the reference `X` (it is what lets a schema
        ;; mention itself); as a statement about which type is meant it is the same one.
        :ref     (do (arity 1)
                     (let [target (child (first args))]
                       (when-not (or (keyword? target) (symbol? target) (string? target))
                         (throw (ex-info (str ":ref takes a name: " (pr-str data)) {:form data})))
                       (into (read-malli target) (props-clauses "ref" props))))
        ;; a function type — the arrow's :in is ordered and (when named) LABELLED, :out is one
        ;; schema, and a guard, when malli's third child is written, is the arrow's one :of.
        :arrow   (do (when-not (<= 2 (count args) 3)
                       (throw (ex-info (str "a function schema is [:=> INPUT OUTPUT] or [:=> INPUT OUTPUT GUARD]: "
                                            (pr-str data)) {:form data})))
                     (let [[input output guard] args]
                       (apply node (list 'out (child output))
                              (cond-> (vec (in-clauses (arrow-params (child input))))
                                guard (conj (list 'of (child guard)))))))
        :flat-arrow (do (when (empty? args)
                          (throw (ex-info (str "[:-> …] needs at least its output: " (pr-str data)) {:form data})))
                        (apply node (list 'out (child (last args)))
                               (in-clauses (map (fn [t] [nil t]) (butlast args)))))
        ;; not an op malli has
        (cond
          (= ::local op)
          (do (arity 1) (-> [(list 'kind "local") (list 'ref (text (first args)))]
                            (into (props-clauses "local" props))))
          ;; `[int? {…}]` handed on by `child` as `[:int? {…}]`
          (and (empty? args) (nil? (namespace op)) (contains? predicate-names (name op)))
          (into [(list 'kind (name op))] (props-clauses (name op) props))
          :else
          (throw (ex-info (str "unsupported malli op: " op " in " (pr-str data)
                               " — malli has no schema type of that name (a reference that"
                               " carries properties is written [:schema {…} Name])")
                          {:form data})))))
    :else (throw (ex-info (str "not a valid malli schema: " (pr-str data) " — records are [:map [:k V] …], not {:k V}")
                          {:data data}))))

(defstructure NamedType
  "Anything a type-reference may NAME: a shape with a name of its own, which a signature points at
   instead of spelling out. A genus and nothing else — it has no slots and no instances of its
   own. A sort becomes nameable by declaring itself a species (`(sub NamedType)`): the design's
   `Kind` is one, and so is whatever a second source of named shapes contributes (a project's
   Lexicon views and attributes). The dialect resolves a reference against the genus and so
   never learns which sorts those are."
  {})

(defstructure ^:value Schema
  "A malli schema, value-identified. `:kind` (a String) is the schema's malli TYPE — the
   keyword's name (`int`, `vector`, `map`, `=>`, `orn`, …), a predicate's (`int?`), or one of
   the two that are not malli's: `ref` (NAMES another type via the :ref name leaf) and `local`
   (names an entry of a local `:registry` in scope). What follows the type lands by SHAPE
   (`op-shapes`): child schemas in :of, in the order written (a collection's element, a
   union's alternatives, `:map-of`'s key then value, an arrow's guard); keyed entries in :field
   (a map's — unordered, like the map they describe) or :entry (every other entry schema's —
   ordered: `:orn`/`:altn` are tried in order, `:catn` is positional); literal values in
   :choice (an enum's members, a comparator's bound, a `:fn` body); an arrow's parameters in
   :in — ordered, each labelled with its name when it has one — and its result in :out. A
   multi-arity `function` holds one arrow per arity in :of.

   Properties: `:min`/`:max` are integer leaves and `:regex` a pattern, all three queryable;
   every other property rides `:props` as text — kept, part of the value's identity, opaque.

   Author as native malli; read-malli expands it. Each spelling is kept as itself
   (`[:maybe X]` is not lowered to `[:or X :nil]`, `int?` is not `:int`), so the print-dual
   returns what was authored. A ref stores the referenced type NAME as a leaf (not an edge),
   so design symbols and code keywords share one representation and a ref is reflectable via
   value-literal->iv; resolution to what it names is by NAME — `names-type` for any
   `NamedType`, `names-kind` for a Kind in particular — and the no-dangling-ref law demands
   every :ref name resolve to one."
  {:kind   :string
   :min    [:? :int]
   :max    [:? :int]
   :regex  [:? :string]
   :props  [:? :string]          ; the remaining properties, as text (opaque; see `props-clauses`)
   :ref    [:? :string]          ; a ref schema's referenced type NAME (resolves by-name to a NamedType)
   :of     [:* Schema]           ; ordered child schemas
   :field  [:set SchemaField]    ; map entries — unordered, like the map they describe
   :entry  [:* SchemaField]      ; the entries of every other entry schema — ordered
   :choice [:* SchemaChoice]     ; literal values in form order (round-trip faithful)
   :in     [:* Schema]           ; arrow params — ordered, each :rel/label-ed with its name
   :out    [:? Schema]}          ; arrow result
  (reader read-malli)
  ;; NO DANGLING REFS: a type-reference must resolve to a modelled NamedType (by name). A ref with
  ;; no :ref name, or a name no named type carries, is a violation — the design pressure that forces every
  ;; referenced type (external/library types included) to be modelled explicitly. Subsumes the old
  ;; "a ref must name a target" presence law.
  ;; TWO laws, because the two failures differ in what a reader can do about them — and what
  ;; each can NAME is the difference. A `^:value` Schema is anonymous, so a law reporting the
  ;; node reports an eid (`["1038"]`), which says a dangling reference exists and nothing about
  ;; which one. The name is the only part anybody can act on, and it is what they will grep for;
  ;; a ref that carries no name has none to give, and there the node is all there is.
  (law "a type-reference names a target"
    {:key :typing/reference-unnamed
     :offenders [?sch]
     :where [[?sch :val/kind "ref"]
             (not-join [?sch] [?sch :val/ref ?_n])]})
  ;; Reporting the NAME also collapses correctly: two signatures referencing the same missing
  ;; Kind are one cause and one finding, rather than one finding per mention.
  ;; ⚠ `:scope :global` is LOAD-BEARING, not tidiness. A self-scoped law injects its sort clause
  ;; on the FIRST offender var, and this one's is `?nm` — a NAME, a string, never a node. Injected
  ;; there the law reads "every string that is a Schema…", which is nothing, and it goes quietly
  ;; vacuous rather than failing. So the sort is pinned on `?sch` in the body instead, where it
  ;; belongs. Changing which var an offender list leads with can silently switch a law off.
  (law "every type-reference resolves to a modelled named type"
    {:scope :global
     :key :typing/reference-unresolved
     :offenders [?nm]
     :where [(is ?sch ::Schema) [?sch :val/kind "ref"] [?sch :val/ref ?nm]
             (not-join [?nm] [?k :entity/name ?nm] (is ?k ::NamedType))]}))

;; ── what it MEANS for two schemas to say the same thing ─────────────────────────────────────
;; The dialect owns the shape of a function type, so it owns the comparison too. Before
;; 2026-08-29 this logic lived in the correspondence law, over `:in`/`:out` slots that
;; `Operation` and `Fn` each carried — fukan restating at the vocabulary altitude what the
;; dialect already modelled. A signature is one `Schema` now, and these relations are how a
;; design one and a code one are checked against each other.

(s/defrelation :arrow-param-at
  "The ?i-th parameter TYPE of arrow schema ?s. The param NAME rides the edge as `:rel/label`
   and is deliberately absent here: a name is design documentation — it is what the print-dual
   renders back — and a code annotation written `[:cat …]` carries none, so comparing names
   would make every positional annotation disagree with a named spec."
  [?s ?i ?t]
  [[?r :rel/from ?s] [?r :rel/kind :in] [?r :rel/to ?t] [?r :rel/order ?i]])

(s/defrelation :arrow
  "A single-arity function type, in either of malli's spellings: `[:=> [:cat A] B]` or the
   flat `[:-> A B]`. Both store their parameters in `:in` and their result in `:out`, so
   everything below compares one against the other without knowing which was written."
  [?s]
  [[?s :val/kind "=>"]]
  [[?s :val/kind "->"]])

(s/defrelation :arrow-differs
  "Two arrow schemas that do not describe the same function: a parameter type at a position the
   other does not have there, or a different result. POSITIONAL — a set of parameter types can
   see neither a reordering nor an arity difference among same-typed params.

   Both ends are bound positively (`arrow`) in every body, and they have to be: a var appearing
   ONLY under negation is not range-restricted, and the engine rejects the rule rather than
   quietly ranging it over the universe. That makes this a product over DISTINCT arrow schemas —
   `Schema` is content-deduped, so it is the number of distinct signatures in the model, not the
   number of functions."
  [?a ?b]
  [(arrow ?a) (arrow ?b) (arrow-param-at ?a ?i ?t) (not-join [?b ?i ?t] (arrow-param-at ?b ?i ?t))]
  [(arrow ?a) (arrow ?b) (arrow-param-at ?b ?i ?t) (not-join [?a ?i ?t] (arrow-param-at ?a ?i ?t))]
  [(arrow ?a) (arrow ?b) (out ?a ?t) (not-join [?b ?t] (out ?b ?t))]
  [(arrow ?a) (arrow ?b) (out ?b ?t) (not-join [?a ?t] (out ?a ?t))]
  ;; a GUARD (`[:=> in out guard]`, the arrow's one `:of`) is part of what the function accepts
  [(arrow ?a) (arrow ?b) (of ?a ?g) (not-join [?b ?g] (of ?b ?g))]
  [(arrow ?a) (arrow ?b) (of ?b ?g) (not-join [?a ?g] (of ?a ?g))])

(s/defrelation :arrow-agrees
  "Two arrow schemas describing the same function."
  [?a ?b]
  [(arrow ?a) (arrow ?b) (not (arrow-differs ?a ?b))])

;; The multi-arity comparison below asks, UNDER A NEGATION, whether an arity has an agreeing
;; arity on the other side. Cozo restricts a positively-called rule to the caller's bindings, but
;; not across a negation: whatever a `not`/`not-join` reaches is evaluated over its whole domain.
;; Reached through `arrow-agrees`, that was `arrow-differs` over EVERY pair of distinct arrow
;; schemas in the model — measured on nido (~2,200 signature schemas) at 114s and ~2 GB of native
;; memory for one law, against 0.5s with the bodies below. So arity agreement is a relation of its
;; own whose every body LEADS with a pair of arities of two function schemas, and the general
;; arrow relations stay outside every negation in `signature-differs`' cone.

(s/defrelation :arity-pair
  "?x and ?y are arities of two multi-arity function schemas (`[:function …]`'s `:of` children).
   The domain arity agreement is decided over: never a pair of arrows that are not both arities."
  [?x ?y]
  [[?a :val/kind "function"] [?b :val/kind "function"] (of ?a ?x) (of ?b ?y) (arrow ?x) (arrow ?y)])

(s/defrelation :arity-differs
  "Two arities of function schemas that do not describe the same function — `arrow-differs`
   restricted to `arity-pair`, its six bodies restated each led by the pair. Restated rather than
   called, because a call would put `arrow-differs` under `signature-differs`' negation.

   ⚠ PERMANENT SEAM: the negation in `signature-differs` evaluates this over EVERY arity pair, so
   the multi-arity check stays quadratic in the number of arities the model's multi-arity function
   schemas hold together (27 of nido's 2,203 signature schemas are multi-arity). Closing it needs
   the law to supply the pairs to compare — demand machinery fukan does not have. A defaults chain
   is better modelled as one arrow with `[:? T]` params, which keeps it out of this product."
  [?x ?y]
  [(arity-pair ?x ?y) (arrow-param-at ?x ?i ?t) (not-join [?y ?i ?t] (arrow-param-at ?y ?i ?t))]
  [(arity-pair ?x ?y) (arrow-param-at ?y ?i ?t) (not-join [?x ?i ?t] (arrow-param-at ?x ?i ?t))]
  [(arity-pair ?x ?y) (out ?x ?t) (not-join [?y ?t] (out ?y ?t))]
  [(arity-pair ?x ?y) (out ?y ?t) (not-join [?x ?t] (out ?x ?t))]
  [(arity-pair ?x ?y) (of ?x ?g) (not-join [?y ?g] (of ?y ?g))]
  [(arity-pair ?x ?y) (of ?y ?g) (not-join [?x ?g] (of ?x ?g))])

(s/defrelation :arity-agrees
  "Two arities of function schemas describing the same function — `arrow-agrees` over `arity-pair`."
  [?x ?y]
  [(arity-pair ?x ?y) (not (arity-differs ?x ?y))])

(s/defrelation :signature-shaped
  "A schema that can be a signature: one arity, or several."
  [?s]
  [(arrow ?s)]
  [[?s :val/kind "function"]])

(s/defrelation :signature-differs
  "Two signatures that do not say the same thing — the comparison an adherence law is written
   against. A signature is an arrow (`:=>`) or a multi-arity function (`[:function …]`), and for
   the multi-arity case each arity must have an agreeing arity on the other side. Arities are
   matched by SHAPE rather than by position, because `[:function A B]` and `[:function B A]`
   declare the same function."
  [?a ?b]
  ;; one arity against several: never the same statement. (Asked of the two SHAPES, not of
  ;; the stored kind — `[:=> …]` and `[:-> …]` are two kinds and one shape.)
  [(arrow ?a) [?b :val/kind "function"]]
  [[?a :val/kind "function"] (arrow ?b)]
  [(arrow-differs ?a ?b)]
  [[?a :val/kind "function"] [?b :val/kind "function"]
   (of ?a ?x) (not-join [?b ?x] (of ?b ?y) (arity-agrees ?x ?y))]
  [[?a :val/kind "function"] [?b :val/kind "function"]
   (of ?b ?y) (not-join [?a ?y] (of ?a ?x) (arity-agrees ?x ?y))])

(s/defrelation :signature-type
  "A type a signature mentions at a parameter or result position — directly for an arrow, and
   through each arity for a multi-arity function. What a cross-module data-dependency reads."
  [?sig ?t]
  [(in ?sig ?t)]
  [(out ?sig ?t)]
  [(of ?sig ?arrow) (in ?arrow ?t)]
  [(of ?sig ?arrow) (out ?arrow ?t)])

;; Where a signature's parameter and result types sit, through each arity of a multi-arity one.
;; Told apart, where `signature-type` deliberately is not: what a function TAKES and what it GIVES
;; are different questions to anything reading the flow of data.
(s/defrelation :signature-in
  "A type a signature takes at a parameter position."
  [?sig ?t]
  [(in ?sig ?t)]
  [(of ?sig ?arrow) (in ?arrow ?t)])

(s/defrelation :signature-out
  "The type a signature gives as its result."
  [?sig ?t]
  [(out ?sig ?t)]
  [(of ?sig ?arrow) (out ?arrow ?t)])

(s/defrelation :names-type
  "A ref Schema ?sch whose name resolves to the named type ?t, of whatever sort — a Kind, or
   anything else declared a `NamedType`. `names-kind` is this narrowed to Kinds."
  [?sch ?t]
  [[?sch :val/kind "ref"] [?sch :val/ref ?nm] [?t :entity/name ?nm] (is ?t ::NamedType)])

;; What a schema is MADE OF, whatever slot the part sits in. The walking relations above know
;; where a signature keeps its types; this one is for a reader who has a type in hand and asks
;; what it mentions — which must not depend on whether the mention sits in a vector's element,
;; a map's field or an `:orn` branch.
(s/defrelation :schema-part
  "Schema ?c is a direct part of schema ?s: a child (`:of`), a keyed entry's schema (a map's
   field, an `:orn`/`:catn`/`:multi` entry), or a function type's parameter or result.
   `schema-part+` is everything a schema is made of, at any depth."
  [?s ?c]
  [(of ?s ?c)]
  [(in ?s ?c)]
  [(out ?s ?c)]
  [(field ?s ?f) (schema ?f ?c)]
  [(entry ?s ?f) (schema ?f ?c)])

;; names-kind — the type-ref → named-Kind navigation as a DEFRELATION (injected into every law/query
;; by check/vocab-rules), so the consumers that chase a ref Schema to the type it names — e.g.
;; `module-depends` data-adoption (fukan.common.vocab.code.subsystem) — read it by name instead of each
;; inlining the 3-clause chain.
(s/defrelation :names-kind
  "a ref Schema ?sch whose :ref name leaf resolves BY NAME to the Kind ?k it references — the
   ref-Schema→named-type navigation (name-based, mirroring how the twin correlates strata)."
  [?sch ?k]
  [[?sch :val/kind "ref"] [?sch :val/ref ?nm] [?k :entity/name ?nm] (Kind ?k)])

;; ── the runtime bridges (the interpretation mechanism) ─────────────────────────

(defn- order-of
  "Sort key for relation eid `r`: its `:rel/order` from `ords` (absent → 0), coerced to long.
   `cq/q` returns leaf cells natively (typed-q), so `:rel/order` arrives a number."
  [ords r]
  (let [o (get ords r)]
    (if (nil? o) 0 (long o))))

(defn- sorted-rel-rows
  "The reified `rel` relations of `from` as `[?r ?to]` rows sorted by :rel/order (relations with no
   :rel/order sort as 0 — harmless for unordered slots) — the shared fetch behind `children`/
   `labelled-children`. Targets and orders are read in two queries and merged in Clojure — `cq/q`
   (Cozo) has no `get-else` builtin to default order."
  [db from rel]
  (let [tos  (cq/q '[:find ?r ?to :in $ ?from ?k :where
                     [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/to ?to]] db from rel)
        ords (into {} (cq/q '[:find ?r ?ord :in $ ?from ?k :where
                              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/order ?ord]] db from rel))]
    (sort-by #(order-of ords (first %)) tos)))

(defn- children
  "Target eids of `from`'s reified `rel` relations, in :rel/order order."
  [db from rel]
  (mapv second (sorted-rel-rows db from rel)))

(defn- labelled-children
  "Like `children` but returns [to label] pairs in :rel/order order — for arrow :in params
   (absent label → \"\"). The shared sorted fetch, plus the per-relation labels."
  [db from rel]
  (let [lbls (into {} (cq/q '[:find ?r ?lbl :in $ ?from ?k :where
                              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/label ?lbl]] db from rel))]
    (mapv (fn [[r to]] [to (get lbls r "")]) (sorted-rel-rows db from rel))))

(defn- choice-value
  "The literal value a SchemaChoice node holds."
  [db ceid]
  (let [c (cq/entity db ceid)
        v (:val/value c)]
    (case (:val/kind c)
      "string" v
      "symbol" (symbol v)
      "edn"    (untext v)
      (keyword v))))

(defn render
  "Render the Schema at `eid` in `db` back to a malli data-form — the form `read-malli` was
   given, with these differences and no others, each one the same schema in malli:

     a reference renders as a KEYWORD, whatever it was written as — `Foo`, `\"Foo\"`, `my.ns/Foo`
       and `[:ref \"Foo\"]` are all `:Foo` (a symbol keeps only its name, which is what it is
       resolved by);
     a reference to a LOCAL registry name renders as `[:ref name]`, the spelling valid anywhere;
     a map's fields render in key order (a map has none), and a fieldless, propertyless map as
     the bare `:map`;
     a map entry naming only its key (`[:map :order/id]`) renders with the reference spelled
       out (`[:map [:order/id :order/id]]`);
     an arrow's input always renders as a `[:cat …]`/`[:catn …]`: the bare `:cat` as `[:cat]`,
       and an input carrying properties as the one parameter it is (`[:=> [:cat {…} T] R]` →
       `[:=> [:cat [:cat {…} T]] R]`);
     a regex OBJECT renders as its pattern string (`#\"a+\"` → `[:re \"a+\"]`), and a predicate
       function object as its symbol;
     an empty property map, `{:optional false}`, and nil in the property position are not
       there (the nil is kept where dropping it would turn the first child into properties:
       `[:enum nil nil 1]`)."
  [db eid]
  (let [ent   (cq/entity db eid)
        kind  (:val/kind ent)
        props (cond-> (if-let [p (:val/props ent)] (into {} (untext p)) {})
                (:val/min ent)   (assoc :min (:val/min ent))
                (:val/max ent)   (assoc :max (:val/max ent))
                (and (= "string" kind) (:val/regex ent)) (assoc :re (:val/regex ent)))
        ;; malli reads a nil or a map right after the head as the PROPERTY position, so a first
        ;; child that is one (`[:enum nil nil 1]` — the enum of nil and 1) needs the position
        ;; filled to stay a child
        form  (fn [head parts]
                (-> (cond (seq props) [head props]
                          (and (seq parts) (or (nil? (first parts)) (map? (first parts)))) [head nil]
                          :else [head])
                    (into parts)))
        atom* (fn [head] (if (seq props) [head props] head))
        kids  (fn [rel] (map #(render db %) (children db eid rel)))
        entry (fn [feid]
                (let [f  (cq/entity db feid)
                      fp (cond-> (if-let [p (:val/props f)] (into {} (untext p)) {})
                           (:val/optional f) (assoc :optional true))]
                  (-> [(label->key (:val/key f))]
                      (cond-> (seq fp) (conj fp))
                      (conj (render db (first (children db feid :schema)))))))
        op    (keyword kind)]
    (cond
      (= "ref" kind)   (atom* (keyword (:val/ref ent)))
      (= "local" kind) (form :ref [(untext (:val/ref ent))])
      (contains? predicate-names kind) (atom* (symbol kind))
      :else
      (case (op-shapes op)
        :leaf          (atom* op)
        (:one :many)   (form op (kids :of))
        ;; `:field` is an UNORDERED slot (a map has no field order), so the relations carry no
        ;; :rel/order and the db's natural row order differs between engines. Canonicalize by
        ;; stored key so the rendered form is deterministic and engine-independent (fields
        ;; compare as a set). A FIELDLESS map renders as the bare `:map` keyword — round-tripping
        ;; how it is authored (`:map` and `[:map]` store identically) and matching the
        ;; bare-scalar convention above, so a `:map`-annotated function signature adheres to its
        ;; `:map`-modelled type.
        :fields        (let [fields (sort-by #(:val/key (cq/entity db %)) (children db eid :field))]
                         (if (and (empty? fields) (empty? props))
                           :map
                           (form :map (map entry fields))))
        :entries       (form op (map entry (children db eid :entry)))
        :values        (form op (map #(choice-value db %) (children db eid :choice)))
        :select        (form op [(first (kids :of)) (mapv #(choice-value db %) (children db eid :choice))])
        :re            (form :re [(:val/regex ent)])
        ;; Params render back in the spelling they were authored in: NAMED ones as `[:catn …]`,
        ;; positional ones as `[:cat …]`. Both are malli's, and a print-dual that returned the
        ;; one the author did not write would be rewriting their code — the same rule `:maybe`
        ;; follows. A partly-named arrow cannot arise: `arrow-params` names all of a `:catn`'s
        ;; params or none of a `:cat`'s.
        :arrow         (let [params (labelled-children db eid :in)
                             ;; `labelled-children` reports an absent label as "", not nil
                             named? (every? (fn [[_ lbl]] (seq (str lbl))) params)]
                         (form :=> (into [(if (and named? (seq params))
                                            (into [:catn] (map (fn [[ieid lbl]] [(keyword lbl) (render db ieid)]) params))
                                            (into [:cat]  (map (fn [[ieid _]] (render db ieid)) params)))
                                          (render db (first (children db eid :out)))]
                                         (kids :of))))
        :flat-arrow    (form :-> (concat (kids :in) (kids :out)))
        ;; TOTAL: an unknown kind cannot occur for a Schema built through `read-malli`, so render
        ;; a visible structured placeholder instead of throwing — keeps the read side total (a
        ;; marker that can never pass as a real malli type), rather than leaking partiality
        ;; upward.
        [:fukan/unrenderable kind]))))

(def ^:private validator
  "Compiled validator per type form (memoized — forms are authored literals, few). Against
   malli's default registry plus `malli.util`'s (`:merge`/`:union`/`:select-keys`), so a
   refined slot can be written in anything the dialect reads and stores."
  (memoize (fn [form] (m/validator form {:registry (merge (m/default-schemas) (mu/schemas))}))))

(defn valid?
  "Does `value` satisfy the malli `type-form`? The refined-slot bridge: a slot
   target like `[:enum \"a\" \"b\"]` or `[:int {:min 1}]` is checked with the full
   malli interpretation — the kernel stores the form verbatim and never reads it."
  [type-form value]
  ((validator type-form) value))

;; ── the dialect wiring (registration into the `typing` SPI) ────────────────────

;; Opting into this grammar wires the dialect: requiring this ns self-registers its bridges + its
;; value-structure tag `:reflect-tag` (the kernel's `reflect-type` builds Schema subgraphs through it —
;; no reflect bridge needed). Merge-per-key, so a composition root could still override any single
;; bridge. (There is no `:adheres?` bridge — signature adherence is STRUCTURAL, the `:signature`
;; comparator comparing decomposed :in/:out node identities; see fukan.common.vocab.code.operation.)
(typing/register-type-dialect! {:valid?      valid?
                                :reflect-tag ::Schema
                                :render      render})
