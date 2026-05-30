(ns fukan.canvas.vocab.validation
  "Methodology vocabulary for systems that run validation pipelines producing
   violation lists. Opt-in: require this namespace only if your project
   structures checks as `(Model) -> [Violation]` entry points.

   Ships one lift: `checker`. The signature is baked in — a checker is
   exactly `(model: Model) -> [Violation]`. Checks with different signatures
   are not checkers; declare them as `function` from construction."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.vocab.construct :as construct]))

(def ^:private checker-shape
  {:kind :arrow
   :inputs {:kind :record
            :fields [["model" {:kind :ref :target :model/Model}]]}
   :outputs {:kind :list :elem {:kind :ref :target :agent/Violation}}})

(defconstructor checker
  "A validation entry point with the standard signature `(Model) -> [Violation]`.
   The full shape is baked in; the lift takes only the name + docstring."

  (produces [name doc forms]
    (construct/build :canvas/checker name checker-shape {} :doc doc)))
