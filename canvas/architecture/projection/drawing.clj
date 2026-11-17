(ns canvas.architecture.projection.drawing
  "Self-spec: the DRAWING projection (`fukan.canvas.projection.drawing`) — a view graph as a
   picture.

   It hides everything that turns visible occurrences and edges into something to look at: the
   layout algorithm and its options, how large a box is, how an edge is routed, what a box, an
   edge and the legend look like, and how the result is written as SVG and as PNG. The page shows
   what this module draws and the CLI writes it to a file, so there is one drawing of a view and
   not one per place it is looked at.

   A Drawing is every one of those decisions as data. The two encoders write it down and decide
   nothing, which is what keeps the image an agent reads and the page a person opens the same
   picture."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.projection.view :as view]))

(Module projection-drawing
  "Lay a view graph out and draw it, as SVG and as PNG."
  (Kind Theme
    "Which palette a drawing is painted in. A property of the drawing asked for, not of the view."
    [:enum "light" "dark"])
  (Kind Mark
    "One thing painted, as exactly one of three tagged alternatives: a rectangle, a line of text,
     or a polyline. Each requires its own geometry and every attribute that decides how it looks,
     so an encoder is handed nothing to fill in. A text states where it starts, so no encoder
     measures one to place it. `:role` says what a mark is for where a viewer may act on it."
    [:or
     [:tuple [:enum :rect] [:map
                            [:x :int] [:y :int] [:width :int] [:height :int]
                            [:radius :int]
                            [:fill :string]
                            [:stroke :string]
                            [:stroke-width :int]
                            [:role {:optional true} :string]]]
     [:tuple [:enum :text] [:map
                            [:x :int] [:y :int]
                            [:text :string]
                            [:font :string]
                            [:size :int]
                            [:fill :string]
                            [:role {:optional true} :string]]]
     [:tuple [:enum :line] [:map
                            [:points [:vector [:tuple :int :int]]]
                            [:stroke :string]
                            [:stroke-width :int]
                            [:role {:optional true} :string]]]])
  (Kind Drawing
    "A view graph laid out and painted: its size, its background, each visible occurrence with
     the marks that draw it — its label among them, as text — each edge with the marks that draw
     it and the caption an encoder that can caption an edge shows for it, and the legend. Boxes are in paint order. Coordinates are whole numbers in one space,
     the drawing's own. `:selected` is how the box a viewer selected is outlined: which box that
     is, is the page's to say, and an image has no viewer, so only the SVG carries it."
    [:map
     [:width :int]
     [:height :int]
     [:background :string]
     [:selected [:map [:stroke :string] [:stroke-width :int]]]
     [:boxes [:vector [:map
                       [:path view/Occurrence]
                       [:open :boolean]
                       [:members :int]
                       [:marks [:vector Mark]]]]]
     [:edges [:vector [:map
                       [:from view/Occurrence]
                       [:to view/Occurrence]
                       [:relation :string]
                       [:count :int]
                       [:title :string]
                       [:marks [:vector Mark]]]]]
     [:legend [:vector Mark]]])
  (Operation draw
    "A view graph as a Drawing in `theme`: every occurrence placed inside its container, every
     edge routed, and each given its marks. Reads nothing but its arguments — no model, no font,
     no clock — so one graph is one drawing on any machine."
    {:signature [:=> [:catn [:graph view/ViewGraph] [:theme Theme]] Drawing]})
  (Operation svg
    "A Drawing written as an SVG document. Each occurrence's marks are grouped under its path, so
     a page can tell which box a viewer acted on, and the Drawing's outline for a selected box is
     carried under a class a page sets on that box's group."
    {:signature [:=> [:catn [:drawing Drawing]] :string]})
  (Operation png
    "A Drawing written as PNG bytes."
    {:signature [:=> [:catn [:drawing Drawing]] :any]
     :performs  [:throws]}))                       ; the platform's image encoder may refuse
