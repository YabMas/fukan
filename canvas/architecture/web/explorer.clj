(ns canvas.architecture.web.explorer
  "Self-spec: the EXPLORER (`fukan.web.explorer`) — the browser view of a held model.

   It hides everything that makes a reading reachable from a browser: the transport, the routes,
   how a ViewGraph is encoded on the wire, the page and its client script, and how a request's
   untrusted parameters become a ViewSpec and a set of open paths. That last one is the single
   place request shape is parsed; the projection it calls is only ever handed well-formed values.

   It is handed a function that yields the current model and calls it on every request. It keeps
   no model and no view state between requests — what a viewer has opened and selected lives in
   the page's address — so a rebuilt model is what the next request reads, and two browser tabs
   are two independent readings."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.projection.view :as view]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.cozo.query :as query]))

(Module explorer
  "Serve the declared design of whatever model a caller supplies as a navigable page."
  (Kind GraphRequest
    "A graph request once parsed: a ViewSpec and the open paths, or the reason it was refused — a
     malformed parameter, or a relation name that is not among those the model offers to view by
     (the view projection's ViewChoices)."
    [:or
     [:tuple [:enum :view] view/ViewSpec [:set view/Occurrence]]
     [:tuple [:enum :rejected] :string]])
  (Kind GraphAnswer
    "What a graph request is answered with: the time the model was read for it, and the graph, the
     refusal its parse produced, the engine's refusal of a selection it cannot compile, or that no
     model is loaded."
    [:map
     [:at :string]
     [:answer [:or
               [:tuple [:enum :graph] view/ViewGraph]
               [:tuple [:enum :rejected] :string]
               [:tuple [:enum :unanswerable] :string]
               [:tuple [:enum :no-model]]]]])
  (Kind ChoicesAnswer
    "What a request for the relations on offer is answered with: the time the model was read for
     it, and those relations or that no model is loaded."
    [:map
     [:at :string]
     [:answer [:or
               [:tuple [:enum :choices] view/ViewChoices]
               [:tuple [:enum :no-model]]]]])
  (Kind DeclarationAnswer
    "What a request for one node's declaration is answered with: the time the model was read for
     it, and the node's sort and authored form, a refusal of a request naming no node, that the
     model declares no such node, or that no model is loaded."
    [:map
     [:at :string]
     [:answer [:or
               [:tuple [:enum :declaration] [:map [:id :string] [:sort :string] [:text :string]]]
               [:tuple [:enum :rejected] :string]
               [:tuple [:enum :missing]]
               [:tuple [:enum :no-model]]]]])
  (Operation parse-request
    "A request's untrusted parameters, read against the relations the model offers to view by, to a
     GraphRequest."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:params [:map-of :string :string]]] GraphRequest]
     :performs  [:throws :state]
     :delegates [view/views]})
  (Operation answer
    "Answer one graph request against the model `model` yields now: parse it, draw it, and turn a
     selection the engine refuses into an :unanswerable answer rather than a failed request."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]
                            [:params [:map-of :string :string]]] GraphAnswer]
     :performs  [:throws :state]                   ; parsing reads the model's relations before the try
     :delegates [parse-request view/view-graph]})
  (Operation choices
    "Answer what a viewer may nest or link by, against the model `model` yields now."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]] ChoicesAnswer]
     :performs  [:throws :state]
     :delegates [view/views]})
  (Operation declaration
    "Answer a request for one node's declaration against the model `model` yields now: the node,
     named by its entity id, as its sort and authored form — only if the design declares it."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]
                            [:params [:map-of :string :string]]] DeclarationAnswer]
     :performs  [:throws :state]
     :delegates [design/declared-nodes instance/instance-text query/q]})
  (Operation start
    "Start serving on `port`, reading the model through `model` on each request. Returns the handle
     `stop` takes. `model` may yield nothing, which a request answers as 'no model loaded'."
    {:signature [:=> [:catn [:port :int] [:model [:=> [:cat] [:maybe substrate/StructureDb]]]] :any]
     :performs  [:io :state :throws]
     :delegates [answer declaration choices]})
  (Operation port
    "The port a server `start` returned listens on — the one it was handed, or the one it got when
     handed 0."
    {:signature [:=> [:catn [:server :any]] :int]})
  (Operation stop
    "Stop the server a `start` returned."
    {:signature [:=> [:catn [:server :any]] :nil]
     :performs  [:io :state]}))
