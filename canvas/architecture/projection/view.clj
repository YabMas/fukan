(ns canvas.architecture.projection.view
  "Self-spec: the VIEW projection (`fukan.canvas.projection.view`) — a project's declared design
   as a graph a person can look at, where every other projection ends in text.

   What is drawn is stated by a view spec the page chooses: the relations whose edges NEST their
   target inside their source, the relations drawn as EDGES, and an optional selection. This module names no sort and no relation of its own, so a project modelled in a
   vocabulary fukan has never seen is drawn by the same code.

   Its own decisions are the four a drawing forces and a document does not: a node held by two
   containers appears once under each, so an occurrence is identified by its PATH; only the roots
   and the members of the paths a viewer opened are returned, so below the root level the answer
   is what was opened; an edge whose end is not visible is carried by that end's nearest visible
   container, so collapsing a box never loses the fact that something inside it is related; and a
   node the nesting says nothing about is counted by sort rather than drawn, so a model whose
   nodes mostly float reads as a gap to close and not as a wall of boxes."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.lens :as lens]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.projection.design :as design]))

(Module projection-view
  "Turn a view spec and the paths a viewer has opened into the visible part of the declared design."
  (Kind ViewSpec
    "What to draw: relation names that nest, relation names drawn as edges, an optional
     selection binding ?n that narrows the declared nodes, whether to draw the nodes the
     nesting says nothing about, and optionally the entity id of one node to stand inside."
    [:map
     [:nest     [:vector :string]]
     [:link     [:vector :string]]
     [:select   {:optional true} [:vector :any]]
     [:unplaced {:optional true} :boolean]
     [:root     {:optional true} :string]])
  (Kind ViewChoices
    "The relations a model offers to view by — those its grammar declares that relate two things
     — each with how many stored edges of it join two declared nodes: a hint for choosing, 0 for
     a derived relation."
    [:map [:relations [:vector [:map [:name :string] [:edges :int]]]]])
  (Kind Occurrence
    "One occurrence of a node in a drawing, as its path: the entity ids from a root down to it.
     Entity ids and never eids, so a path means the same node after the model is rebuilt."
    [:vector :string])
  (Kind ViewGraph
    "The visible graph: each node an occurrence with its sort, label and whether it has members to
     open; each edge a link relation between two visible occurrences with how many declared edges
     it carries; and, by sort, how many selected nodes the nesting says nothing about."
    [:map
     [:nodes [:vector [:map
                       [:path [:vector :string]]
                       [:sort :string]
                       [:label :string]
                       [:members :int]
                       [:open :boolean]]]]
     [:edges [:vector [:map
                       [:from [:vector :string]]
                       [:to [:vector :string]]
                       [:relation :string]
                       [:count :int]]]]
     [:unplaced [:vector [:map [:sort :string] [:count :int]]]]])
  (Operation views
    "The relations a model offers to view by: every relation its reflected grammar declares that
     relates two things — stored or derived, whether or not any edge of it occurs — as what a
     viewer may choose to nest or link by. A property of one node and a relation of three are
     left out: neither can nest or be drawn as an edge."
    {:signature [:=> [:catn [:db substrate/StructureDb]] ViewChoices]
     :performs  [:throws :state]
     :delegates [design/declared-nodes query/q]})
  (Operation view-graph
    "The visible graph of `spec` with `open` paths expanded, over the declared nodes its selection
     narrows to: the root level under the nesting relations — or, for a rooted spec, the direct
     members of its root — the direct members of every visible
     open path, each link edge carried by the nearest visible occurrence of either end, and a
     count by sort of the nodes the nesting leaves unplaced."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:spec ViewSpec] [:open [:set Occurrence]]] ViewGraph]
     :performs  [:throws :state]
     :delegates [design/declared-nodes lens/focus-nodes query/q]}))
