(ns canvas.vocab.code.extractors.clojure.operation
  "Clojure grounding for the generic code `Operation` vocabulary.

   Given clj-kondo var-definitions, this namespace decides which Clojure vars are
   Operations and builds extracted Operation facts. The generic `Operation` structure
   and its modelling/readings live in `canvas.vocab.code.operation`."
  (:require [fukan.canvas.core.substrate :as sub]
            [canvas.vocab.code.effect :refer [Effect]]
            [canvas.vocab.code.operation :as operation]))

(def fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit.
   `defn`/`defn-` are functions; `defmulti` is a POLYMORPHIC operation (a dispatch fn with a
   uniform signature its co-owned methods implement) — a concrete surface, not a split-ownership
   plug-point. `def`, `defmacro`, `defmethod`, etc. stay excluded."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmulti})

(defn extract-operation
  "Build an extracted Operation InstanceValue from a clj-kondo var-definition `v`
   and the set of effect keywords `effs` directly attributed to it."
  [v effs]
  (sub/->InstanceValue ::operation/Operation (str (:name v)) nil
                       (cond-> {:val/private (boolean (:private v))}
                         (:export (:meta v))       (assoc :val/export true)
                         (:test-support (:meta v)) (assoc :val/test-support true)
                         (:malli/schema (:meta v)) (assoc :val/sig (pr-str (:malli/schema (:meta v)))))
                       (cond-> []
                         (seq effs) (conj {:rk :performs :card :many
                                           :targets (mapv (fn [eff] (Effect eff)) (sort effs))}))
                       false))
