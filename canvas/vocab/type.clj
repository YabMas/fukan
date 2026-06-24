(ns canvas.vocab.type
  "The malli type DIALECT — fukan's realization of the kernel's `typing` plug-point. A foundational
   vocab primitive: the code vocab (Kind shapes, Operation signatures) and grammar reflection both
   build on it. This is the HOOK side of the typing SPI — the plug-point + bridge SHAPE stay in
   `fukan.canvas.core.typing`; requiring this namespace self-registers the full dialect (all four
   bridges + its value-structure tag), so a model carries its type checking, reflection, rendering,
   and adherence by opting in.

   A richer Shape: malli's grammar modelled as content-deduped `^:value` structures, so a schema is a
   queryable subgraph (plain `d/q`), never a `pr-str` blob. The core stays blind — it sees an opaque
   schema reference; this dialect owns ALL interpretation (`render`/`valid?`/`sigs-adhere?`)."
  (:require [malli.core :as m]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]))

;; ── the runtime bridges (the hook into the typing plug-point) ──────────────────

(defn- order-of
  "Sort key for relation eid `r`: its `:rel/order` from `ords` (absent → 0), coerced to long.
   `cq/q` returns all cells as STRINGS over Cozo and native numbers over datascript, so the
   numeric order is normalized to stay correct on both engines."
  [ords r]
  (let [o (get ords r)]
    (cond (nil? o) 0, (string? o) (Long/parseLong o), :else (long o))))

(defn- children
  "Target eids of `from`'s reified `rel` relations, in :rel/order order (relations with no
   :rel/order sort as 0 — harmless for unordered slots). Targets and orders are read in two
   queries and merged in Clojure — `cq/q` (Cozo) has no `get-else` builtin to default order."
  [db from rel]
  (let [tos  (cq/q '[:find ?r ?to :in $ ?from ?k :where
                     [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/to ?to]] db from rel)
        ords (into {} (cq/q '[:find ?r ?ord :in $ ?from ?k :where
                              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/order ?ord]] db from rel))]
    (->> tos (sort-by #(order-of ords (first %))) (mapv second))))

(defn- labelled-children
  "Like `children` but returns [to label] pairs in :rel/order order — for arrow :in params
   (absent label → \"\"). Same two-query merge as `children`, plus the labels."
  [db from rel]
  (let [tos  (cq/q '[:find ?r ?to :in $ ?from ?k :where
                     [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/to ?to]] db from rel)
        ords (into {} (cq/q '[:find ?r ?ord :in $ ?from ?k :where
                              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/order ?ord]] db from rel))
        lbls (into {} (cq/q '[:find ?r ?lbl :in $ ?from ?k :where
                              [?r :rel/from ?from] [?r :rel/kind ?k] [?r :rel/label ?lbl]] db from rel))]
    (->> tos (sort-by #(order-of ords (first %)))
         (mapv (fn [[r to]] [to (get lbls r "")])))))

(defn render
  "Render the Schema at `eid` in `db` back to a malli data-form."
  [db eid]
  (let [ent   (cq/entity db eid)
        kind  (:val/kind ent)
        props (cond-> {}
                (:val/min ent)   (assoc :min (:val/min ent))
                (:val/max ent)   (assoc :max (:val/max ent))
                (:val/regex ent) (assoc :re  (:val/regex ent)))]
    (case kind
      ("int" "string" "boolean" "keyword" "double" "any" "nil")
      (if (seq props) [(keyword kind) props] (keyword kind))
      ("vector" "set" "sequential")
      [(keyword kind) (render db (first (children db eid :of)))]
      ("tuple" "or" "and")
      (into [(keyword kind)] (map #(render db %) (children db eid :of)))
      "map"
      ;; `:field` is an UNORDERED slot (a map has no field order), so the relations carry no
      ;; :rel/order and the db's natural row order differs between engines. Canonicalize by field
      ;; key so the rendered form is deterministic and engine-independent (fields compare as a set).
      (into [:map]
            (sort-by first
              (map (fn [feid]
                     (let [f   (cq/entity db feid)
                           sk  (first (children db feid :schema))
                           kw  (keyword (:val/key f))
                           sub (render db sk)]
                       (if (:val/optional f) [kw {:optional true} sub] [kw sub])))
                   (children db eid :field))))
      "enum"
      (into [:enum]
            (map (fn [ceid]
                   (let [c (cq/entity db ceid)
                         v (:val/value c)]
                     (case (:val/kind c)
                       "string" v
                       "symbol" (symbol v)
                       (keyword v))))
                 (children db eid :choice)))
      "ref"
      (keyword (:entity/name (cq/entity db (first (children db eid :names)))))
      "map-of"
      (let [[k v] (children db eid :of)] [:map-of (render db k) (render db v)])
      "=>"
      [:=> (into [:catn] (map (fn [[ieid lbl]] [(keyword lbl) (render db ieid)])
                              (labelled-children db eid :in)))
       (render db (first (children db eid :out)))]
      ;; TOTAL: an unknown kind cannot occur for a well-formed Schema (validated at construction),
      ;; so render a visible structured placeholder instead of throwing — keeps the read side total
      ;; (a marker that can never pass as a real malli type), rather than leaking partiality upward.
      [:fukan/unrenderable kind])))

(def ^:private validator
  "Compiled validator per type form (memoized — forms are authored literals, few)."
  (memoize m/validator))

(defn valid?
  "Does `value` satisfy the malli `type-form`? The refined-slot bridge: a slot
   target like `[:enum \"a\" \"b\"]` or `[:int {:min 1}]` is checked with the full
   malli interpretation — the kernel stores the form verbatim and never reads it."
  [type-form value]
  ((validator type-form) value))

(defn- normalize-fn-schema
  "Normalize a malli function-schema `[:=> [:cat IN…] OUT]` to `{:in [IN…] :out OUT}`
   (the empty `[:cat]` yields `:in []`), or `nil` when `form` is not a well-formed
   `[:=> [:cat …] OUT]`. The `:in` is a VECTOR (a sequence) so order and arity are
   preserved. Returning `nil` for malformed input means two malformed forms never
   compare equal — a malformed signature adheres to nothing."
  [form]
  (when (and (vector? form) (= :=> (first form)) (>= (count form) 3)
             (vector? (second form)) (= :cat (first (second form))))
    {:in (vec (rest (second form))) :out (nth form 2)}))

(defn sigs-adhere?
  "Whether a code function-schema ADHERES to a modelled Operation's type. Both are
   malli `[:=> [:cat IN…] OUT]` forms; they adhere iff both are well-formed AND their
   OUT types are equal AND their input type SEQUENCES (order + arity) are equal.
   `:in` is an ordered slot on the modelled Operation, so argument ORDER and ARITY are
   both fidelity-checked: `[:=> [:cat :A :B] :R]` does NOT adhere to `[:=> [:cat :B :A] :R]`,
   and a 2-arg `[:cat :int :int]` does NOT adhere to a 1-arg `[:cat :int]`."
  [model-form code-form]
  (let [m (normalize-fn-schema model-form)
        c (normalize-fn-schema code-form)]
    (boolean (and m c (= m c)))))

;; Opting into this grammar wires the FULL dialect: requiring this ns self-registers all four
;; bridges + its value-structure tag `:reflect-tag` (the kernel's `reflect-type` builds Schema
;; subgraphs through it — no reflect bridge needed). Merge-per-key, so a composition root could
;; still override any single bridge.
(typing/register-type-dialect! {:valid?      valid?
                                :reflect-tag ::Schema
                                :render      render
                                :adheres?    sigs-adhere?})

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

(defn ^:export read-malli
  "Expand a native malli data-literal into Schema construction clauses (one level).
   Accepts only valid malli structural syntax:
     :int :string :boolean :keyword :double   scalar leaf
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
     Foo  (a bare symbol)                      a var-ref naming a Kind: a `ref`
                                               schema that NAMES the type `Foo` via a
                                               `:names` edge (var-captured at the
                                               authoring site)

   A bare keyword is a scalar; a bare symbol is a var-ref naming a Kind (a named
   schema). Enum members must be keywords, strings, or symbols — passed raw; the
   SchemaChoice reader preserves the member type, so forms round-trip exactly."
  [data]
  (cond
    (keyword? data) [(list 'kind (name data))]
    (symbol?  data) [(list 'kind "ref") (list 'names data)]
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
          (let [[input output] args]
            (into [(list 'kind "=>") (list 'out output)]
                  (map (fn [[pname ptype]] (list 'in [pname ptype]))
                       (catn->pairs input))))
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
   ref (names another type via the :names edge), or arrow (=> — labelled :in
   params + :out). Author as native malli; read-malli expands it. The ref edge
   targets the wildcard `Any` (the named type's var is captured at the authoring
   site) to avoid a require cycle on the vocab that owns the named Kind."
  {:kind   :string
   :min    [:? :int]
   :max    [:? :int]
   :regex  [:? :string]
   :names  [:? Any]
   :of     [:* Schema]           ; ordered children (tuple/or/and/map-of are form-faithful)
   :field  [:set SchemaField]    ; map entries — unordered, like the map they describe
   :choice [:* SchemaChoice]     ; enum members in form order (round-trip faithful)
   :in     [:* Schema]           ; arrow params — ordered, each :rel/label-ed with its name
   :out    [:? Schema]}          ; arrow result
  (reader read-malli)
  (law "a ref schema must name a target"
    (has :names :when {:kind "ref"})))
