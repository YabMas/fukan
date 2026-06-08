(ns canvas.dialects.malli
  "The malli schema DIALECT — fukan-the-project's chosen type language.

   A richer Shape: malli's grammar modelled as content-deduped `^:value`
   structures, so a schema is a queryable subgraph (plain `d/q`), never a
   `pr-str` blob. The core stays blind — it sees an opaque schema reference;
   this dialect, and its src/-side bridges (render now; parse/adheres? later),
   own all interpretation.

   Vocab-only canvas spec (no build-canvas); auto-discovered under canvas/**."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defn ^:export read-choice
  "An enum member (already a string) -> SchemaChoice clauses."
  [s]
  [(list 'value s)])

(defstructure ^:value SchemaChoice
  "One member of an enum schema, value-identified (`:red` shared across enums)."
  (slot :value (one :String))
  (reader read-choice))

(defn ^:export read-field
  "A map entry `[key-name schema-form optional?]` -> SchemaField clauses. The
   schema-form is re-expanded by the interpreter (SchemaField's :schema targets
   Schema, which carries read-malli)."
  [[k v opt]]
  [(list 'key k) (list 'schema v) (list 'optional opt)])

(defstructure ^:value SchemaField
  "A labelled map entry: key + its Schema + optionality."
  (slot :key      (one :String))
  (slot :schema   (one Schema))
  (slot :optional (one :Bool))
  (reader read-field))

(defn ^:export read-malli
  "Expand a native malli data-literal into Schema construction clauses (one level).
   A SUPERSET of the old `read-shape` — fukan's `[X]` / `{:k V}` literals are also
   accepted, so any Shape re-reads as a Schema unchanged:
     :int :string :boolean :keyword :double   scalar leaf
     [:int {:min _ :max _}] / [:string {:re}]  scalar + constraint leaves
     [:vector|:set|:sequential X]              collection of element X
     [:tuple A B ...] / [:or ...] / [:and ...]  ordered/alternative children :of
     [:map [:k V] [:k {:optional true} V]]     labelled :field entries
     [:enum :a :b]                             :choice members
     [:re \"pat\"]                               string + regex (normalizes to the
                                               same datoms as [:string {:re \"pat\"}])
     [X]   (vector, non-keyword head)          fukan's list-of shorthand → a
                                               `vector` schema of element X
     {:k V, …}  (a map literal)                fukan's record shorthand → a `map`
                                               schema with required fields k: V
     Foo  (a bare symbol)                      a `ref` schema that NAMES the type
                                               `Foo` via a `:names` edge (var-captured
                                               at the authoring site)

   Enum members must be keywords or symbols — each is passed through `(name …)`."
  [data]
  (cond
    (keyword? data) [(list 'kind (name data))]
    (symbol?  data) [(list 'kind "ref") (list 'names data)]
    (map?     data)
    (into [(list 'kind "map")]
          (map (fn [[k v]] (list 'field [(name k) v false])) data))
    (vector?  data)
    (let [[op & more] data]
      (if (keyword? op)
        (let [props (when (map? (first more)) (first more))
              args  (if props (rest more) more)]
          (case op
            (:int :string :boolean :keyword :double)
            (cond-> [(list 'kind (name op))]
              (:min props) (conj (list 'min (:min props)))
              (:max props) (conj (list 'max (:max props)))
              (:re  props) (conj (list 'regex (str (:re props)))))
            (:vector :set :sequential)
            [(list 'kind (name op)) (list 'of (first args))]
            (:tuple :or :and)
            [(list 'kind (name op)) (list 'of (vec args))]
            :map
            (into [(list 'kind "map")]
                  (map (fn [[k & rest-entry]]
                         (let [fp (when (map? (first rest-entry)) (first rest-entry))
                               v  (if fp (second rest-entry) (first rest-entry))]
                           (list 'field [(name k) v (boolean (:optional fp))])))
                       args))
            :enum
            (into [(list 'kind "enum")] (map #(list 'choice (name %)) args))
            :re
            [(list 'kind "string") (list 'regex (str (first args)))]
            (throw (ex-info (str "unsupported malli op: " op) {:form data}))))
        ;; non-keyword head ⇒ fukan's [X] shorthand: a homogeneous vector of X
        (do (when (next data)
              (throw (ex-info "list-of shorthand [X] takes exactly one element" {:form data})))
            [(list 'kind "vector") (list 'of (first data))])))
    :else (throw (ex-info (str "not a malli schema literal: " (pr-str data))
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
  (slot :kind   (one :String))
  (slot :min    (optional :Int))
  (slot :max    (optional :Int))
  (slot :regex  (optional :String))
  (slot :names  (optional Any))
  (slot :of     (ordered Schema))
  (slot :field  (many SchemaField))
  (slot :choice (many SchemaChoice))
  (reader read-malli)
  (law "a ref schema must name a target"
    :offenders '[?s]
    :where '[[?s :val/kind "ref"]
             (not-join [?s] [?r :rel/from ?s] [?r :rel/kind :names])]))
