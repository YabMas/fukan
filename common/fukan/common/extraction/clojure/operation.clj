(ns fukan.common.extraction.clojure.operation
  "Clojure grounding for the generic code `Operation` vocabulary.

   Given clj-kondo var-definitions, this namespace decides which Clojure vars are
   Operations and builds extracted Operation facts. The generic `Operation` structure
   and its modelling/readings live in `fukan.common.vocab.code.operation`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.effect :refer [Effect]]
            [fukan.common.vocab.code.operation :as operation]))

(def ^:private schema-tag
  "The type dialect's ^:value structure tag — the fact-side signature builds its :in/:out
   Schema subgraphs through it (via `s/value-literal->iv`, the one value-construction path)."
  :fukan.common.typing.malli/Schema)

(defn- code-arrow->in-out
  "Decompose an EXTRACTED code function-schema `[:=> INPUT OUTPUT]` into `{:in [type…] :out type}`.
   Unlike the authoring `arrow->in-out`, INPUT may be POSITIONAL `[:cat T…]` (code's convention —
   no param names) as well as named `[:catn [:n T]…]`; param names are dropped, since adherence
   compares argument TYPES and ORDER, not names."
  [form]
  (let [[_ input output] form
        in (case (first input)
             :cat  (vec (rest input))
             :catn (mapv second (rest input))
             [])]
    {:in in :out output}))

(def fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit.
   `defn`/`defn-` are functions; `defmulti` is a POLYMORPHIC operation (a dispatch fn with a
   uniform signature its co-owned methods implement) — a concrete surface, not a split-ownership
   plug-point. `def`, `defmacro`, `defmethod`, etc. stay excluded."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmulti})

(defn extract-operation
  "Build an extracted Operation InstanceValue from a clj-kondo var-definition `v`
   and the set of effect keywords `effs` directly attributed to it. When `v` carries a
   `:malli/schema` function-type, the signature is DECOMPOSED into `:in`/`:out` Schema
   subgraphs (the fact-side symmetric with the design side), built through the type dialect
   via `s/value-literal->iv` — the queryable form the adherence comparator reads (there is no
   `:val/sig` blob; both strata render through `operation-sig`)."
  [v effs]
  (let [sig    (:malli/schema (:meta v))
        arrow? (and (vector? sig) (= :=> (first sig)) (= 3 (count sig)))
        {:keys [in out]} (when arrow? (code-arrow->in-out sig))]
    (sub/->InstanceValue ::operation/Operation (str (:name v)) nil
                         (cond-> {:val/private (boolean (:private v))}
                           (:export (:meta v))       (assoc :val/export true)
                           (:test-support (:meta v)) (assoc :val/test-support true))
                         (cond-> []
                           (seq effs) (conj {:rk :performs :card :many
                                             :targets (mapv (fn [eff] (Effect eff)) (sort effs))})
                           (seq in)   (conj {:rk :in :card :many
                                             :targets (mapv #(s/value-literal->iv schema-tag %) in)})
                           arrow?     (conj {:rk :out :card :optional
                                             :targets [(s/value-literal->iv schema-tag out)]}))
                         false)))
