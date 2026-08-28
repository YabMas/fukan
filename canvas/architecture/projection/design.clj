(ns canvas.architecture.projection.design
  "Self-spec: the DESIGN projection (`fukan.canvas.projection.design`) — the two print-duals
   composed into the document a reader actually arrives for: what has this project declared?

   Its own contribution is the SCOPING, and neither exclusion is a heuristic: the reflection
   meta-grammar is present in every model and authored by none, and an anonymous `^:value` node
   is already rendered inline by the slot that holds it. What remains is derived from the
   INSTANCES — a vocabulary appears because the project instantiated something from it, so a
   grammar merely loaded is not mistaken for a design."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.projection.grammar :as grammar]
            [canvas.architecture.projection.instance :as instance]))

(Module projection-design
  "Render a project's own declared design as one document."
  (Operation declared-nodes
    "The eids of the project's own declared instances — named, and not the meta-grammar's —
     paired with their structure tag."
    {:signature [:=> [:catn [:db substrate/StructureDb]] :any]
     :performs  [:throws :state]
     :delegates [query/q]})
  (Operation declared-vocabularies
    "The namespaces of the vocabularies the project actually INSTANTIATED, sorted."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector :string]]
     :performs  [:throws :state]
     :delegates [declared-nodes]})
  (Operation design-text
    "The declared design as one document: each vocabulary used, then every instance, both as
     their authored forms."
    {:signature [:=> [:catn [:db substrate/StructureDb]] instance/Text]
     :performs  [:throws :state]
     :delegates [declared-nodes declared-vocabularies grammar/vocabulary-primer instance/focus-text]}))
