(ns fukan.canvas.vocab.lifecycle
  "Methodology vocabulary for systems with stateful modules exposing optional
   read accessors. Opt-in: require this namespace only if your project
   structures state reads as `() -> Optional<T>` callables.

   Ships one lift: `getter`. The signature shape is baked in: zero-arg input,
   Optional output. The lift takes name + docstring + the inner return type."
  (:require [fukan.canvas.core.shape :as shape]
            [fukan.canvas.vocab.construct :as construct]
            [fukan.canvas.vocab.registry :as registry]))

(def tag-definitions
  [{:tag :canvas/getter :family :Affordance :payload :arrow
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A zero-arg accessor returning Optional<T>."}])

(registry/register! tag-definitions)

(defn getter
  "Declare a zero-arg Optional<T> accessor on the enclosing module.
   Example: (getter \"get_port\" \"Current bound port, if running.\" :Integer)"
  [name doc return-type-expr]
  (let [output-shape {:kind :optional :inner (shape/parse return-type-expr)}
        full-shape   {:kind :arrow
                      :inputs  {:kind :record :fields []}
                      :outputs output-shape}]
    (construct/build :canvas/getter name full-shape {} :doc doc)))
