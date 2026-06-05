(ns fukan.canvas.core.assemble
  (:require [fukan.canvas.core.structure :as s]))

(defn collect
  "Seq of [var InstanceValue] for every instance-bearing interned var across the
   given namespaces."
  [ns-syms]
  (for [ns-sym ns-syms
        [_ v] (ns-interns ns-sym)
        :when (s/instance-value? (deref v))]
    [v (deref v)]))
