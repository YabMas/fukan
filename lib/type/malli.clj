(ns lib.type.malli
  "The malli type DIALECT — one entry in the shared-lib's pluggable type-authoring
   surface (`lib.type.*`). A consuming model selects it by requiring this namespace; it
   is generic, not fukan-specific, so it lives in the reusable `lib/` stdlib rather than
   fukan's canvas vocab.

   A richer Shape: malli's grammar modelled as content-deduped `^:value` structures, so a
   schema is a queryable subgraph (plain `d/q`), never a `pr-str` blob. The core stays
   blind — it sees an opaque schema reference; this dialect, and its src/-side bridges
   (render now; parse/adheres? later), own all interpretation.

   Opt-in (required, not auto-discovered like `canvas/**`); ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            [fukan.dialect.malli :as dialect]))

;; Opting into this grammar wires its checking: a refined slot target (e.g.
;; `{:polarity [:enum "design-down" "code-up"]}`) compiles to a law that checks
;; values through the type-dialect plug-point — so the grammar registers the
;; malli `:valid?` bridge at load (merge-per-key; a composition root adds the rest).
(typing/register-type-dialect! {:valid? dialect/valid?})

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
  {:value :String
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
  {:key      :String
   :schema   Schema
   :optional :Bool}
  (reader read-field))

(defn ^:export read-malli
  "Expand a native malli data-literal into Schema construction clauses (one level).
   Accepts only valid malli structural syntax:
     :int :string :boolean :keyword :double   scalar leaf
     [:int {:min _ :max _}] / [:string {:re}]  scalar + constraint leaves
     [:vector|:set|:sequential X]              collection of element X
     [:tuple A B ...] / [:or ...] / [:and ...]  ordered/alternative children :of
     [:map [:k V] [:k {:optional true} V]]     labelled :field entries
     [:enum :a :b] / [:enum \"a\" \"b\"]           :choice members (type preserved)
     [:re \"pat\"]                               string + regex (normalizes to the
                                               same datoms as [:string {:re \"pat\"}])
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
          (throw (ex-info (str "unsupported malli op: " op) {:form data})))))
    :else (throw (ex-info (str "not a valid malli schema: " (pr-str data) " — records are [:map [:k V] …], not {:k V}")
                          {:data data}))))

(defstructure ^:value Schema
  "A malli schema, value-identified. `:kind` (a String) is the combinator:
   scalar (int/string/boolean/keyword/double — with :min/:max/:regex leaves),
   collection (vector/set/sequential — one element in :of), tuple/or/and
   (children in :of), map (labelled :field entries), enum (:choice members),
   or ref (names another type via the :names edge). Author as native malli;
   read-malli expands it. The ref edge targets the wildcard `Any` (the named
   type's var is captured at the authoring site) to avoid a require cycle on the
   vocab that owns the named Kind."
  {:kind   :String
   :min    [:? :Int]
   :max    [:? :Int]
   :regex  [:? :String]
   :names  [:? Any]
   :of     [:* Schema]           ; ordered children (tuple/or/and are form-faithful)
   :field  [:set SchemaField]    ; map entries — unordered, like the map they describe
   :choice [:* SchemaChoice]}    ; enum members in form order (round-trip faithful)
  (reader read-malli)
  (law "a ref schema must name a target"
    (has :names :when {:kind "ref"})))
