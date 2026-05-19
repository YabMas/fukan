(ns fukan.target.clojure.projector
  "Projector — assembles Implementation Blueprints on demand.

   Plan 6 fills this in. The Projector consumes the same project layer
   registry the Analyzer uses (Plan 5) and applies the same six-component
   universal projection mechanic in reverse: spec primitive → Blueprint."
  (:require [fukan.target.clojure.blueprint :as bp]))

(defn project
  "Project a primitive into a Blueprint. Stub: returns identity Blueprint.
   Tasks 1-6 implement the six-component assembly."
  [_model _registry _primitive-id _projection-kind]
  (bp/make {:primitive-id _primitive-id
            :projection-kind _projection-kind
            :address {:ns "" :name ""}
            :artifact-kind :code/function}))
