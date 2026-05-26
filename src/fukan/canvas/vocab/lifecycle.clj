(ns fukan.canvas.vocab.lifecycle
  "Methodology vocabulary for systems with stateful modules exposing optional
   read accessors. Opt-in: require this namespace only if your project
   structures state reads as `() -> Optional<T>` callables.

   Ships one lift: `getter`. The signature shape is baked in: zero-arg input,
   Optional output. The lift takes name + docstring + the inner return type."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.shape :as shape]))

(defn- emit-refs!
  "Walk a parsed shape; for each :ref encountered, transact a :references
   Relation from from-id to the ref's target keyword."
  [from-id parsed-shape]
  (case (:kind parsed-shape)
    :ref      (h/declare-relation from-id :references (:target parsed-shape))
    :optional (emit-refs! from-id (:inner parsed-shape))
    :list     (emit-refs! from-id (:elem parsed-shape))
    :set      (emit-refs! from-id (:elem parsed-shape))
    :sum      (run! #(emit-refs! from-id %) (:variants parsed-shape))
    :record   (run! (fn [[_ s]] (emit-refs! from-id s)) (:fields parsed-shape))
    nil))

(defn getter
  "Declare a zero-arg Optional<T> accessor on the enclosing module.
   Example: (getter \"get_port\" \"Current bound port, if running.\" :Integer)"
  [name _doc return-type-expr]
  (let [inner-shape  (shape/parse return-type-expr)
        output-shape {:kind :optional :inner inner-shape}
        full-shape   {:kind :arrow
                      :inputs  {:kind :record :fields []}
                      :outputs output-shape}
        aff (h/declare-affordance name
              :role  :canvas/getter
              :shape full-shape)]
    (emit-refs! (:id aff) output-shape)
    aff))
