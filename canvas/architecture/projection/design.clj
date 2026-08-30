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
            [canvas.architecture.kernel.lens :as lens]
            [canvas.architecture.projection.grammar :as grammar]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.projection.prose :as prose]))

(Module projection-design
  "Render a project's own declared design as one document."
  (Operation declared-nodes
    "The eids of the project's own declared instances — named, and not the meta-grammar's —
     paired with their structure tag."
    {:signature [:=> [:catn [:db substrate/StructureDb]] :any]
     :performs  [:throws :state]
     :delegates [query/q]})
  (Operation vocabularies-of
    "The namespaces of the vocabularies a node set INSTANTIATES, sorted. Takes the nodes rather
     than the db because a SELECTED document must narrow its concepts with its instances."
    {:signature [:=> [:catn [:nodes [:sequential :any]]] [:vector :string]]})
  (Operation design-text
    "The declared design as one document, in one of two registers, optionally narrowed by a
     datalog selection: :forms renders the authored declarations, :prose renders the same
     declarations as sentences."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:register :keyword]
                            [:select [:? [:maybe :any]]]] instance/Text]
     :performs  [:throws :state]
     :delegates [declared-nodes vocabularies-of lens/focus-nodes query/q
                 grammar/vocabulary-primer grammar/structure-form
                 instance/focus-text instance/instance-form
                 prose/structure-prose prose/instance-prose]})
  (Operation design-index
    "The design's table of contents: every sort declared, how many, and the selection that
     fetches it. What a selection is useless without — asking for one sort presupposes knowing
     that sort exists, and the only thing that said so was the whole document."
    {:signature [:=> [:catn [:db substrate/StructureDb]] instance/Text]
     :performs  [:throws :state]
     :delegates [declared-nodes design-text]}))
