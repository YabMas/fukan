(ns canvas.typing.malli
  "The malli type dialect's runtime BRIDGES — the mechanism half of the plugin (the vocabulary half,
   `Schema`/`SchemaChoice`/`SchemaField`, is `canvas.typing`). `render` walks a reified Schema subgraph
   back to a malli data-form; `valid?` checks a value against a refined type form. The core stays blind
   (it sees an opaque schema reference); these bridges own ALL interpretation. `canvas.typing` wires
   them into the `typing` SPI at load (`register-type-dialect!`)."
  (:require [malli.core :as m]
            [fukan.cozo.query :as cq]))

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
      ("int" "string" "boolean" "keyword" "double" "symbol" "any" "nil")
      (if (seq props) [(keyword kind) props] (keyword kind))
      ("vector" "set" "sequential")
      [(keyword kind) (render db (first (children db eid :of)))]
      ("tuple" "or" "and")
      (into [(keyword kind)] (map #(render db %) (children db eid :of)))
      "map"
      ;; `:field` is an UNORDERED slot (a map has no field order), so the relations carry no
      ;; :rel/order and the db's natural row order differs between engines. Canonicalize by field
      ;; key so the rendered form is deterministic and engine-independent (fields compare as a set).
      ;; A FIELDLESS map renders as the bare `:map` keyword — round-tripping how it is authored
      ;; (`:map` and `[:map]` store identically) and matching the bare-scalar convention above,
      ;; so a `:map`-annotated function signature adheres to its `:map`-modelled type.
      (let [fields (children db eid :field)]
        (if (empty? fields)
          :map
          (into [:map]
                (sort-by first
                  (map (fn [feid]
                         (let [f   (cq/entity db feid)
                               sk  (first (children db feid :schema))
                               kw  (keyword (:val/key f))
                               sub (render db sk)]
                           (if (:val/optional f) [kw {:optional true} sub] [kw sub])))
                       fields)))))
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
      (keyword (:val/ref ent))
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
