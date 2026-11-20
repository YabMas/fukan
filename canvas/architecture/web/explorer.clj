(ns canvas.architecture.web.explorer
  "Self-spec: the EXPLORER (`fukan.web.explorer`) — the browser view of a held model.

   It hides everything that makes a reading reachable from a browser: the transport, the routes,
   how an answer is encoded on the wire, the page and its client script, and how a request's
   untrusted parameters become a ViewSpec, a set of open paths and a palette. That last one is the single
   place request shape is parsed; the projection it calls is only ever handed well-formed values.

   The parameters of a graph request are also the view parameters the page writes into its
   address and sends — same names, same encodings — so what is parsed here is the one grammar a
   view is asked in, by the page, by a host that links to it, and by the CLI. `address` writes
   it, from those parameters. The ids a viewer dived through to reach the root are kept beside
   them as a trail only the page reads, as the selected path is. An address carrying the root an
   earlier page wrote, a JSON array of ids, is replaced by the page, before anything is asked,
   with one whose root is the array's last id and whose trail is the ids before it.

   The page draws nothing: it shows the drawing this module answers, which the drawing
   projection made, and keeps what a viewer does with it — of the drawing it says only which box
   is selected.

   It is handed a function that yields the current model and calls it on every request. It keeps
   no model and no view state between requests — what a viewer has opened and selected lives in
   the page's address — so a rebuilt model is what the next request reads, and two browser tabs
   are two independent readings."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.projection.view :as view]
            [canvas.architecture.projection.drawing :as drawing]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.cozo.query :as query]))

(Module explorer
  "Serve the declared design, or one plane, of whatever model a caller supplies as a navigable page."
  (Kind GraphRequest
    "A graph request once parsed: a ViewSpec, the open paths and the palette to paint in — light
     when the request names none — or the reason it was refused: a malformed parameter, a palette
     that is not a Theme, or a relation name that is not among those the model offers to view by
     (the view projection's ViewChoices)."
    [:or
     [:tuple [:enum :view] view/ViewSpec [:set view/Occurrence] drawing/Theme]
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
  (Kind DrawingAnswer
    "What a drawing request is answered with: the time the model was read for it, and the view's
     drawing as SVG with how many nodes it shows and its count of unplaced nodes, or exactly the
     refusals a graph request has."
    [:map
     [:at :string]
     [:answer [:or
               [:tuple [:enum :drawing] [:map
                                         [:svg :string]
                                         [:nodes :int]
                                         [:unplaced [:vector [:map [:sort :string] [:count :int]]]]]]
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
     it, and the node's sort and form, a refusal of a request naming no node, that the universe
     of the request's plane — the declared design when it names none — holds no such node, or
     that no model is loaded."
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
  (Operation drawing
    "Answer one drawing request against the model `model` yields now: parse it as a graph
     request, palette included, and answer it as `answer` answers that request, with the graph
     drawn in the parsed palette."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]
                            [:params [:map-of :string :string]]] DrawingAnswer]
     :performs  [:throws :state]
     :delegates [parse-request view/view-graph drawing/draw drawing/svg]})
  (Operation address
    "A graph request's parameters as the query string that carries them — to this module's
     transport, which reads it back as those parameters, and in the page's address."
    {:signature [:=> [:catn [:params [:map-of :string :string]]] :string]})
  (Operation choices
    "Answer what a viewer may nest or link by, against the model `model` yields now."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]] ChoicesAnswer]
     :performs  [:throws :state]
     :delegates [view/views]})
  (Operation declaration
    "Answer a request for one node's declaration against the model `model` yields now: the node,
     named by its entity id, as its sort and form — only if the universe of the plane the request
     names, or the declared design when it names none, holds it."
    {:signature [:=> [:catn [:model [:=> [:cat] [:maybe substrate/StructureDb]]]
                            [:params [:map-of :string :string]]] DeclarationAnswer]
     :performs  [:throws :state]
     :delegates [view/universe instance/instance-text query/q]})
  (Operation start
    "Start serving on `port`, reading the model through `model` on each request. Returns the handle
     `stop` takes. `model` may yield nothing, which a request answers as 'no model loaded'."
    {:signature [:=> [:catn [:port :int] [:model [:=> [:cat] [:maybe substrate/StructureDb]]]] :any]
     :performs  [:io :state :throws]
     :delegates [answer drawing declaration choices]})
  (Operation port
    "The port a server `start` returned listens on — the one it was handed, or the one it got when
     handed 0."
    {:signature [:=> [:catn [:server :any]] :int]})
  (Operation stop
    "Stop the server a `start` returned."
    {:signature [:=> [:catn [:server :any]] :nil]
     :performs  [:io :state]}))
