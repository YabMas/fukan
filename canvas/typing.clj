(ns canvas.typing
  "The malli type DIALECT — fukan's realization of the kernel's `typing` plug-point. A self-contained
   PLUGIN in its OWN area (`canvas/typing`), NOT a general-vocab primitive: it owns a SPECIALIZED
   vocabulary (malli modelled as data — `Schema`/`SchemaChoice`/`SchemaField`, here) plus the runtime
   bridges that interpret it (`render`/`valid?`, split into `canvas.typing.malli` — the mechanism
   half), both specific to malli. This ns is the vocabulary half + the dialect wiring; requiring it
   loads the bridges and registers the whole dialect. The code vocab (Kind shapes, Operation
   signatures) and grammar reflection consume it. This is the HOOK side of the typing SPI — the
   plug-point + bridge SHAPE stay in `fukan.canvas.core.typing`; requiring this namespace self-registers
   the full dialect (all four bridges + its value-structure tag), so a model carries its type checking,
   reflection, rendering, and adherence by opting in.

   A richer Shape: malli's grammar modelled as content-deduped `^:value` structures, so a schema is a
   queryable subgraph (plain `d/q`), never a `pr-str` blob. The core stays blind — it sees an opaque
   schema reference; this dialect owns ALL interpretation (`render`/`valid?`)."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            [canvas.typing.malli :as malli]))

;; Opting into this grammar wires the dialect: requiring this ns self-registers the malli runtime
;; bridges (`canvas.typing.malli` — the mechanism half) + its value-structure tag `:reflect-tag`
;; (the kernel's `reflect-type` builds Schema subgraphs through it — no reflect bridge needed).
;; Merge-per-key, so a composition root could still override any single bridge. (There is no
;; `:adheres?` bridge — signature adherence is STRUCTURAL, the `:signature` comparator comparing
;; decomposed :in/:out node identities; see canvas.vocab.code.module.)
(typing/register-type-dialect! {:valid?      malli/valid?
                                :reflect-tag ::Schema
                                :render      malli/render})

;; ── the authoring grammar (Schema as queryable ^:value structures) ─────────────

(defn ^:export catn->pairs
  "Parse a malli function-input schema into ordered [param-name-symbol type-form] pairs —
   the SHARED arrow-input representation used by both Operation's `:signature` sugar and
   the `:=>` Schema combinator. `[:catn [:name T] …]` → pairs; `[:cat]` → []; a positional
   `[:cat Type …]` is rejected (name your parameters)."
  [input]
  (when-not (vector? input)
    (throw (ex-info (str "function input must be [:catn …] or [:cat]: " (pr-str input)) {:form input})))
  (let [[op & more] input]
    (case op
      :catn (mapv (fn [pair]
                    (when-not (and (vector? pair) (= 2 (count pair)) (keyword? (first pair)))
                      (throw (ex-info (str ":catn entry must be [:name Type]: " (pr-str pair)) {:form input})))
                    [(symbol (name (first pair))) (second pair)])
                  more)
      :cat  (if (seq more)
              (throw (ex-info (str "name your parameters — use [:catn [:name Type] …], not [:cat …]: " (pr-str input)) {:form input}))
              [])
      (throw (ex-info (str "function input must be [:catn …] or [:cat]: " (pr-str input)) {:form input})))))

(defn ^:export arrow->in-out
  "Decompose a malli function schema `[:=> INPUT OUTPUT]` into `{:in [[param-name type] …]
   :out OUTPUT}` — the SHARED arrow representation both Operation's `:signature` sugar and the
   `:=>` Schema combinator build on. INPUT is parsed by `catn->pairs` (named params → ordered,
   labelled pairs; `[:cat]` → no `:in`). Rejects a non-`[:=> …]` form."
  [form]
  (when-not (and (vector? form) (= :=> (first form)) (= 3 (count form)))
    (throw (ex-info (str "signature must be a malli function schema [:=> INPUT OUTPUT]: " (pr-str form)) {:form form})))
  (let [[_ input output] form]
    {:in (catn->pairs input) :out output}))

(defn ^:export read-choice
  "An enum member (a keyword, string, or symbol — passed RAW) -> SchemaChoice
   clauses. The member's TYPE is preserved as :kind, so `[:enum :a]` and
   `[:enum \"a\"]` are distinct values and forms round-trip exactly."
  [m]
  [(list 'value (name m))
   (list 'kind (cond (keyword? m) "keyword"
                     (string? m)  "string"
                     (symbol? m)  "symbol"
                     :else (throw (ex-info (str "enum member must be a keyword, string, or symbol: "
                                                (pr-str m)) {:member m}))))])

(defstructure ^:value SchemaChoice
  "One member of an enum schema, value-identified by (name, member kind) — `:red`
   is one node shared across enums, distinct from `\"red\"`."
  {:value :string
   :kind  [:enum "keyword" "string" "symbol"]}
  (reader read-choice))

(defn ^:export read-field
  "A map entry `[key-name schema-form optional?]` -> SchemaField clauses. The
   schema-form is re-expanded by the interpreter (SchemaField's :schema targets
   Schema, which carries read-malli)."
  [[k v opt]]
  [(list 'key k) (list 'schema v) (list 'optional opt)])

(defstructure ^:value SchemaField
  "A labelled map entry: key + its Schema + optionality."
  {:key      :string
   :schema   Schema
   :optional :boolean}
  (reader read-field))

(def ^:private builtin-scalar-kinds
  "Bare malli keywords the dialect interprets as scalar/opaque kinds — the single source of
   truth for read-malli's keyword partition. A bare keyword IN this set is a scalar leaf; any
   OTHER bare keyword is a name-ref to a Kind (matching malli's model: a keyword is a registry
   lookup, builtin here vs a domain name). `map` is included — a bare `:map` is a fieldless map."
  #{"int" "string" "boolean" "keyword" "double" "symbol" "any" "nil" "map"})

(defn ^:export read-malli
  "Expand a native malli data-literal into Schema construction clauses (one level).
   Accepts only valid malli structural syntax:
     :int :string :boolean :keyword :double :symbol   scalar leaf
     :any :nil                                 opaque/void scalar leaves
     [:int {:min _ :max _}] / [:string {:re}]  scalar + constraint leaves
     [:vector|:set|:sequential X]              collection of element X
     [:tuple A B ...] / [:or ...] / [:and ...]  ordered/alternative children :of
     [:map [:k V] [:k {:optional true} V]]     labelled :field entries
     [:enum :a :b] / [:enum \"a\" \"b\"]           :choice members (type preserved)
     [:re \"pat\"]                               string + regex (normalizes to the
                                               same datoms as [:string {:re \"pat\"}])
     [:=> [:catn [:name T] …] Out]             function type — labelled :in params + :out
     [:map-of K V]                             homogeneous map — key schema + value schema
     Foo  (a bare symbol)                      a `ref` schema NAMING the type `Foo` —
     :Foo (a non-builtin bare keyword)         the referenced type name in a `:ref`
                                               leaf (no edge; resolution to the Kind
                                               is the by-name `names-kind` relation)

   A builtin bare keyword (int/string/…, see `builtin-scalar-kinds`) is a scalar; ANY
   other bare keyword, or a bare symbol, is a name-ref to a Kind — both reduce to the
   same `{:kind \"ref\", :ref \"<Name>\"}` (malli's model: a keyword is a registry lookup,
   builtin vs a domain name). Enum members must be keywords, strings, or symbols — passed raw; the
   SchemaChoice reader preserves the member type, so forms round-trip exactly."
  [data]
  (cond
    (keyword? data) (if (contains? builtin-scalar-kinds (name data))
                      [(list 'kind (name data))]
                      [(list 'kind "ref") (list 'ref (name data))])
    (symbol?  data) [(list 'kind "ref") (list 'ref (name data))]
    (vector?  data)
    (let [[op & more] data]
      (when-not (keyword? op)
        (throw (ex-info (str "a malli schema vector needs a keyword head — write [:vector X], not " (pr-str data))
                        {:form data})))
      (let [props (when (map? (first more)) (first more))
            args  (if props (rest more) more)]
        (case op
          (:int :string :boolean :keyword :double)
          (cond-> [(list 'kind (name op))]
            (:min props) (conj (list 'min (:min props)))
            (:max props) (conj (list 'max (:max props)))
            (:re  props) (conj (list 'regex (str (:re props)))))
          (:vector :set :sequential)
          ;; one element — clause args are varargs now (no vector splicing), so a
          ;; vector schema element (e.g. [:vector [:map …]]) passes through as one
          ;; reader literal.
          [(list 'kind (name op)) (list 'of (first args))]
          (:tuple :or :and)
          [(list 'kind (name op)) (cons 'of args)]
          :map
          (into [(list 'kind "map")]
                (map (fn [[k & rest-entry]]
                       (let [fp (when (map? (first rest-entry)) (first rest-entry))
                             v  (if fp (second rest-entry) (first rest-entry))]
                         (list 'field [(name k) v (boolean (:optional fp))])))
                     args))
          :enum
          (into [(list 'kind "enum")] (map #(list 'choice %) args))
          :re
          [(list 'kind "string") (list 'regex (str (first args)))]
          :=>
          ;; a function type — the arrow's :in is LABELLED (param name) + ordered, :out is one
          ;; schema, exactly as code/operation stores a signature (the shared representation).
          (let [{:keys [in out]} (arrow->in-out (into [:=>] args))]
            (into [(list 'kind "=>") (list 'out out)]
                  (map (fn [[pname ptype]] (list 'in [pname ptype])) in)))
          :map-of
          ;; two ordered :of children — the key schema then the value schema
          [(list 'kind "map-of") (cons 'of args)]
          (throw (ex-info (str "unsupported malli op: " op) {:form data})))))
    :else (throw (ex-info (str "not a valid malli schema: " (pr-str data) " — records are [:map [:k V] …], not {:k V}")
                          {:data data}))))

(defstructure ^:value Schema
  "A malli schema, value-identified. `:kind` (a String) is the combinator:
   scalar (int/string/boolean/keyword/double — with :min/:max/:regex leaves),
   collection (vector/set/sequential — one element in :of), tuple/or/and
   (children in :of), map (labelled :field entries), map-of (two ordered :of
   children — key then value), enum (:choice members),
   ref (NAMES another type via the :ref name leaf), or arrow (=> — labelled :in
   params + :out). Author as native malli; read-malli expands it. A ref stores the
   referenced type NAME as a leaf (not an edge), so design symbols and code keywords
   share one representation and a ref is reflectable via value-literal->iv; resolution
   to the named Kind is the by-name `names-kind` relation, and the coming
   no-dangling-ref law demands every :ref name resolve to a modelled Kind."
  {:kind   :string
   :min    [:? :int]
   :max    [:? :int]
   :regex  [:? :string]
   :ref    [:? :string]          ; a ref schema's referenced type NAME (resolves by-name to a Kind)
   :of     [:* Schema]           ; ordered children (tuple/or/and/map-of are form-faithful)
   :field  [:set SchemaField]    ; map entries — unordered, like the map they describe
   :choice [:* SchemaChoice]     ; enum members in form order (round-trip faithful)
   :in     [:* Schema]           ; arrow params — ordered, each :rel/label-ed with its name
   :out    [:? Schema]}          ; arrow result
  (reader read-malli)
  ;; NO DANGLING REFS: a type-reference must resolve to a modelled Kind (by name). A ref with no
  ;; :ref name, or a name no Kind carries, is a violation — the design pressure that forces every
  ;; referenced type (external/library types included) to be modelled explicitly. Subsumes the old
  ;; "a ref must name a target" presence law.
  (law "every type-reference resolves to a modelled Kind"
    :offenders '[?sch]
    :where '[[?sch :val/kind "ref"]
             (not-join [?sch]
               [?sch :val/ref ?nm]
               [?k :entity/name ?nm]
               (Kind ?k))]))

;; names-kind — the type-ref → named-Kind navigation as a DEFRELATION (injected into every law/query
;; by check/vocab-rules), so the consumers that chase a ref Schema to the type it names — e.g.
;; `module-depends` data-adoption (canvas.vocab.code.module) — read it by name instead of each
;; inlining the 3-clause chain.
(s/defrelation :names-kind
  "a ref Schema ?sch whose :ref name leaf resolves BY NAME to the Kind ?k it references — the
   ref-Schema→named-type navigation (name-based, mirroring how the twin correlates strata)."
  '[?sch ?k]
  '[[?sch :val/kind "ref"] [?sch :val/ref ?nm] [?k :entity/name ?nm] (Kind ?k)])
