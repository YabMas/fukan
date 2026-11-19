(ns fukan.canvas.projection.drawing
  "The DRAWING projection: a view graph as a picture.

   `draw` turns the visible graph the view projection answers into a DRAWING — every position,
   size and paint decision as data — and `svg` and `png` write that Drawing down. The page shows
   the SVG and the CLI writes both, so a view has one drawing and not one per place it is looked
   at; and because the two encoders only write what the Drawing states, the image an agent reads
   and the page a person opens are the same picture.

   Three things are decided here and nowhere else:

   - LAYOUT is Eclipse ELK's layered algorithm over the nesting the paths spell out. A box's size
     is a function of its label's LENGTH, never of a font: a font differs per machine, and a
     drawing that moved with it could not be compared across two of them.
   - PAINT is a colour derived from a sort's or a relation's NAME, so no sort and no relation is
     known here and a vocabulary this namespace has never seen is painted by the same code.
   - A MARK IS COMPLETE. A text states where it starts and a line every point it passes, so an
     encoder measures nothing and holds no constant that changes what the picture looks like."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Font RenderingHints]
           [java.awt.geom Path2D$Double RoundRectangle2D$Double]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]
           [org.eclipse.elk.alg.layered.options LayeredMetaDataProvider LayeredOptions OrderingStrategy]
           [org.eclipse.elk.core RecursiveGraphLayoutEngine]
           [org.eclipse.elk.core.data ILayoutMetaDataProvider LayoutMetaDataService]
           [org.eclipse.elk.core.math ElkPadding]
           [org.eclipse.elk.core.options CoreOptions Direction HierarchyHandling]
           [org.eclipse.elk.core.util BasicProgressMonitor]
           [org.eclipse.elk.graph ElkBendPoint ElkEdge ElkEdgeSection ElkNode]
           [org.eclipse.elk.graph.util ElkGraphUtil]))

;; ── paint ─────────────────────────────────────────────────────────────────────

(def ^:private palettes
  "What each theme paints the things a view graph does not name: the ground, a box, its text."
  {"light" {:ground "#f6f5f1" :box "#ffffff" :box-open "#efede6" :ink "#1d1c1a" :muted "#6d6a63"
            :accent "#2f5fd0" :sort-light 42 :relation-light 40}
   "dark"  {:ground "#17181a" :box "#26272b" :box-open "#1c1d20" :ink "#e9e7e1" :muted "#9a978f"
            :accent "#8fb0ff" :sort-light 62 :relation-light 66}})

(def ^:private font "sans-serif")

(defn- hue
  "A hue in [0, 360) from a name: the same name is the same colour in every drawing."
  [s]
  (mod (reduce (fn [h c] (bit-and (+ (* h 31) (int c)) 0xFFFFFFFF)) 0 s) 360))

(defn- hsl
  "An HSL colour as `#rrggbb` — the one spelling both encoders read."
  [h s l]
  (let [s (/ s 100.0) l (/ l 100.0)
        c (* (- 1 (Math/abs (- (* 2 l) 1))) s)
        x (* c (- 1 (Math/abs (- (mod (/ h 60.0) 2) 1))))
        m (- l (/ c 2))
        [r g b] (nth [[c x 0] [x c 0] [0 c x] [0 x c] [x 0 c] [c 0 x]] (int (/ h 60)))]
    (apply format "#%02x%02x%02x" (map #(Math/round (* 255 (+ % m))) [r g b]))))

(defn- sort-colour [palette sort] (hsl (hue sort) 45 (:sort-light palette)))
(defn- relation-colour [palette rel] (hsl (hue (str "rel:" rel)) 60 (:relation-light palette)))

(defn- short-name
  "A qualified name without its namespace — what there is room for inside a box."
  [qualified]
  (subs qualified (inc (or (str/last-index-of qualified "/") -1))))

;; ── size ──────────────────────────────────────────────────────────────────────

(defn- text-width
  "How wide `text` is taken to be at `size`: a function of its length alone. Generous on purpose
   — a label that is given more room than its glyphs take still reads, one given less is cut."
  [text size]
  (long (Math/ceil (* 0.6 size (count text)))))

(def ^:private leaf-height 40)
(def ^:private toggle-width 44)
(def ^:private margin 20)

(defn- box-width
  "The least width a box needs for its label, its sort and — when it has members — its marker."
  [{:keys [label sort members]}]
  (max 96 (+ 24
             (max (text-width label 12) (text-width (short-name sort) 10))
             (if (pos? members) toggle-width 0))))

;; ── layout ────────────────────────────────────────────────────────────────────

(defonce ^:private elk
  ;; ELK looks its algorithms up in a process-wide service that must be told about them once
  (delay (.registerLayoutMetaDataProviders
          (LayoutMetaDataService/getInstance)
          (into-array ILayoutMetaDataProvider [(LayeredMetaDataProvider.)]))
         (RecursiveGraphLayoutEngine.)))

(defn- lay-out-once
  "One run of the layout over `graph`, each container in `widen` (path → units) given that much
   more room on either side of what it holds: `{:width :height :at {path [x y w h]} :routes
   [points per edge, in the graph's edge order]}`, every coordinate a whole number in the
   drawing's own space. An occurrence sits inside the one whose path is its own less its last
   id."
  [{:keys [nodes edges]} widen]
  (let [root    (ElkGraphUtil/createGraph)
        parent? (into #{} (map (comp pop :path)) nodes)
        ;; parents before members: the paths are sorted, and a prefix sorts before its extensions
        made    (reduce (fn [made {:keys [path] :as node}]
                          (let [^ElkNode n (ElkGraphUtil/createNode (get made (pop path) root))
                                side (+ 14.0 (get widen path 0))]
                            (if (parent? path)
                              (.setProperty n CoreOptions/PADDING (ElkPadding. 40.0 side 14.0 side))
                              (.setDimensions n (double (box-width node)) (double leaf-height)))
                            (assoc made path n)))
                        {} nodes)
        wires   (mapv (fn [{:keys [from to]}] (ElkGraphUtil/createSimpleEdge (made from) (made to))) edges)]
    (doto root
      (.setProperty CoreOptions/ALGORITHM "org.eclipse.elk.layered")
      (.setProperty CoreOptions/DIRECTION Direction/DOWN)
      (.setProperty CoreOptions/HIERARCHY_HANDLING HierarchyHandling/INCLUDE_CHILDREN)
      (.setProperty CoreOptions/PADDING (ElkPadding. (double margin)))
      (.setProperty CoreOptions/SPACING_NODE_NODE (double 22))
      (.setProperty CoreOptions/SPACING_EDGE_NODE (double 14))
      (.setProperty LayeredOptions/SPACING_NODE_NODE_BETWEEN_LAYERS (double 46))
      (.setProperty LayeredOptions/CONSIDER_MODEL_ORDER_STRATEGY OrderingStrategy/NODES_AND_EDGES))
    (.layout ^RecursiveGraphLayoutEngine @elk root (BasicProgressMonitor.))
    (let [whole  #(Math/round (double %))
          origin (fn origin [^ElkNode n]
                   (if (or (nil? n) (identical? n root))
                     [0.0 0.0]
                     (mapv + [(.getX n) (.getY n)] (origin (.getParent n)))))]
      {:width  (whole (.getWidth root))
       :height (whole (.getHeight root))
       :at     (into {} (map (fn [[path ^ElkNode n]]
                               (let [[x y] (origin n)]
                                 [path (mapv whole [x y (.getWidth n) (.getHeight n)])])))
                     made)
       :routes (mapv (fn [^ElkEdge e]
                       ;; a section's points are relative to the node the edge is contained in
                       (let [[ox oy] (origin (.getContainingNode e))]
                         (vec (for [^ElkEdgeSection s (.getSections e)
                                    [x y] (concat [[(.getStartX s) (.getStartY s)]]
                                                  (map (fn [^ElkBendPoint p] [(.getX p) (.getY p)]) (.getBendPoints s))
                                                  [[(.getEndX s) (.getEndY s)]])]
                                [(whole (+ ox x)) (whole (+ oy y))]))))
                     wires)})))

(defn- lay-out
  "Place `graph`'s occurrences and route its edges, as `lay-out-once` answers, with every box
   at least as wide as its own label needs.

   A container's width is what it holds, which is not known until it is laid out, and can come
   out narrower than the container's own label. Such a container is given the room it lacks and
   the graph is laid out again; widening one can only widen what contains it, so this settles.
   (ELK's own minimum-size option is not used: under a top-to-bottom direction it was measured
   to apply the minimum WIDTH to a container's height.)"
  [graph]
  (loop [widen {} rounds 0]
    (let [laid  (lay-out-once graph widen)
          short (for [node (:nodes graph)
                      :let [lack (- (box-width node) (nth ((:at laid) (:path node)) 2))]
                      :when (pos? lack)]
                  [(:path node) (+ (get widen (:path node) 0) (quot (inc lack) 2))])]
      (if (or (empty? short) (= rounds 8))
        laid
        (recur (into widen short) (inc rounds))))))

;; ── marks ─────────────────────────────────────────────────────────────────────

(defn- text-mark [x y text size fill]
  [:text {:x x :y y :text text :font font :size size :fill fill}])

(defn- box-marks
  "What draws one occurrence at `[x y w h]`: its outline, its label and sort, and — when it has
   members — the marker a viewer opens and closes it by."
  [palette {:keys [label sort members open]} [x y w h]]
  (let [count-text (str (if open "- " "+ ") members)]
    (cond-> [[:rect {:x x :y y :width w :height h :radius 7
                     :fill (if open (:box-open palette) (:box palette))
                     :stroke (sort-colour palette sort) :stroke-width 1 :role "box"}]
             (text-mark (+ x 12) (+ y 17) label 12 (:ink palette))
             (text-mark (+ x 12) (+ y 31) (short-name sort) 10 (:muted palette))]
      (pos? members)
      (into [[:rect {:x (- (+ x w) toggle-width) :y (+ y 4) :width (- toggle-width 4) :height 22 :radius 4
                     :fill "none" :stroke "none" :stroke-width 0 :role "toggle"}]
             (assoc-in (text-mark (- (+ x w) 10 (text-width count-text 11)) (+ y 19) count-text 11 (:muted palette))
                       [1 :role] "toggle")]))))

(defn- arrow-head
  "The two strokes of an open arrow head at the end of `points`, pointing the way the last
   segment runs."
  [points]
  (let [[[ax ay] [bx by]] (take-last 2 points)
        dx (- bx ax) dy (- by ay)
        len (max 1.0 (Math/sqrt (+ (* dx dx) (* dy dy))))
        ux (/ dx len) uy (/ dy len)
        back (fn [side] [(Math/round (- bx (* 9 ux) (* side 4 (- uy))))
                         (Math/round (- by (* 9 uy) (* side 4 ux)))])]
    [(back 1) [bx by] (back -1)]))

(defn- edge-marks
  "What draws one edge along `points`: the line, thicker the more declared edges it carries, its
   arrow head, and the count where it carries more than one."
  [palette {:keys [relation count]} points]
  (let [colour (relation-colour palette relation)
        width  (min 4 (inc (long (/ (Math/log count) (Math/log 2)))))]
    (if (< (clojure.core/count points) 2)
      []
      (cond-> [[:line {:points points :stroke colour :stroke-width width}]
               [:line {:points (arrow-head points) :stroke colour :stroke-width width}]]
        (> count 1)
        (conj (let [i (quot (dec (clojure.core/count points)) 2)
                    [[ax ay] [bx by]] [(nth points i) (nth points (inc i))]]
                (text-mark (+ (quot (+ ax bx) 2) 4) (- (quot (+ ay by) 2) 3) (str count) 10 (:muted palette))))))))

(defn- legend
  "The key to the colours, laid in rows no wider than `width` from `top` down: a stroke for each
   relation drawn, a swatch for each sort shown. `[marks bottom]`."
  [palette {:keys [nodes edges]} width top]
  (let [items (concat (for [rel (distinct (map :relation edges))]
                        {:label rel :swatch :line :colour (relation-colour palette rel)})
                      (for [sort (sort (distinct (map :sort nodes)))]
                        {:label (short-name sort) :swatch :rect :colour (sort-colour palette sort)}))]
    (loop [[{:keys [label swatch colour] :as item} & more] items
           x margin, y top, marks []]
      (if-not item
        [marks (if (seq marks) (+ y 22) top)]
        (let [w (+ 24 (text-width label 11) 14)]
          (if (and (> (+ x w) width) (> x margin))
            (recur (cons item more) margin (+ y 18) marks)
            (recur more (+ x w) y
                   (conj marks
                         (if (= :line swatch)
                           [:line {:points [[x (+ y 6)] [(+ x 18) (+ y 6)]] :stroke colour :stroke-width 2}]
                           [:rect {:x (+ x 4) :y (+ y 1) :width 10 :height 10 :radius 3
                                   :fill colour :stroke "none" :stroke-width 0}])
                         (text-mark (+ x 24) (+ y 10) label 11 (:muted palette))))))))))

(defn- reach
  "The furthest right and furthest down any of `marks` paints, `[x y]` — a text reaching as far
   as its width is taken to be, so a label is never stated past the drawing's edge."
  [marks]
  (reduce (fn [[rx ry] [kind {:keys [x y width height points text size]}]]
            (let [[mx my] (case kind
                            :rect [(+ x width) (+ y height)]
                            :text [(+ x (text-width text size)) y]
                            :line [(apply max (map first points)) (apply max (map second points))])]
              [(max rx mx) (max ry my)]))
          [0 0] marks))

(defn ^{:malli/schema [:=> [:cat :ViewGraph :Theme] :Drawing]}
  draw
  "`graph` as a Drawing in `theme` (\"light\" or \"dark\"): every occurrence placed inside the
   one that contains it, every edge routed, and each given the marks that paint it, with a legend
   beneath. Boxes are in paint order — a container before what it holds.

   Reads its two arguments and nothing else: no model, no font, no clock. The same graph is the
   same Drawing on any machine."
  [graph theme]
  (let [palette (palettes theme)
        {:keys [width height at routes]} (lay-out graph)
        wide    (max width 240)
        [key-marks bottom] (legend palette graph wide (+ height 4))
        boxes   (mapv (fn [{:keys [path open members] :as node}]
                        {:path path :open open :members members
                         :marks (box-marks palette node (at path))})
                      (:nodes graph))
        edges   (mapv (fn [{:keys [from to relation count] :as edge} points]
                        (assoc edge
                               ;; what an encoder that can caption an edge shows for it
                               :title (str (peek from) " —" relation "→ " (peek to)
                                           (when (> count 1) (str " (" count " declared edges)")))
                               :marks (edge-marks palette edge points)))
                      (:edges graph) routes)
        ;; a mark past the layout (a legend entry longer than the graph is wide, a count beside
        ;; an edge at the rim) widens the drawing, so every mark stated lies inside its size; the
        ;; room kept beyond it is the gap after a legend entry across, a text's descent down, so
        ;; a drawing nothing overruns keeps the size it had
        [rx ry] (reach (concat (mapcat :marks boxes) (mapcat :marks edges) key-marks))]
    {:width      (max wide (+ rx 14))
     :height     (max bottom height (* 2 margin) (+ ry 8))
     :background (:ground palette)
     :selected   {:stroke (:accent palette) :stroke-width 2}
     :boxes      boxes
     :edges      edges
     :legend     key-marks}))

;; ── SVG ───────────────────────────────────────────────────────────────────────

(defn- xml [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- tag
  "An SVG element: `attrs` in the order given, `body` (already XML) inside when there is one."
  ([name attrs] (tag name attrs nil))
  ([name attrs body]
   (str "<" name
        (apply str (for [[k v] (partition 2 attrs) :when (some? v)] (str " " k "=\"" (xml v) "\"")))
        (if body (str ">" body "</" name ">") "/>"))))

(defn- svg-mark [[kind {:keys [x y width height radius fill stroke stroke-width text font size points role]}]]
  (case kind
    :rect (tag "rect" ["x" x "y" y "width" width "height" height "rx" radius
                       "fill" fill "stroke" stroke "stroke-width" stroke-width "data-role" role])
    :text (tag "text" ["x" x "y" y "font-family" font "font-size" size "fill" fill "data-role" role]
               (xml text))
    :line (tag "polyline" ["points" (str/join " " (map (fn [[px py]] (str px "," py)) points))
                           "fill" "none" "stroke" stroke "stroke-width" stroke-width "data-role" role])))

(defn ^{:malli/schema [:=> [:cat :Drawing] :string]}
  svg
  "`drawing` as an SVG document.

   Each occurrence is one group carrying its path as JSON in `data-path`, with `data-open` and
   `data-members`, so a page can tell which box a viewer acted on and what acting on it can do;
   a mark's role is its `data-role`. The outline of a SELECTED box is written once, as a style
   for the class `sel` on a box's group: which box that is, is the page's to say."
  [{:keys [width height background selected boxes edges legend]}]
  (let [marks #(apply str (map svg-mark %))]
    (tag "svg" ["xmlns" "http://www.w3.org/2000/svg" "width" width "height" height
                "viewBox" (str "0 0 " width " " height)]
         (str (tag "style" []
                   (str "g.sel > [data-role=\"box\"]{stroke:" (xml (:stroke selected))
                        ";stroke-width:" (:stroke-width selected) "}"))
              (tag "rect" ["width" width "height" height "fill" background])
              (apply str (for [{:keys [path open members] :as box} boxes]
                           (tag "g" ["class" "box" "data-path" (json/generate-string path)
                                     "data-open" open "data-members" members]
                                (marks (:marks box)))))
              (apply str (for [edge edges]
                           (tag "g" ["class" "edge"]
                                (str (tag "title" [] (xml (:title edge)))
                                     (marks (:marks edge))))))
              (tag "g" ["class" "legend"] (marks legend))))))

;; ── PNG ───────────────────────────────────────────────────────────────────────

(defn- colour ^Color [hex] (Color/decode hex))

(defn- paint-mark
  [^java.awt.Graphics2D g [kind {:keys [x y width height radius fill stroke stroke-width text font size points]}]]
  (case kind
    :rect (let [shape (RoundRectangle2D$Double. x y width height (* 2 radius) (* 2 radius))]
            (when-not (= "none" fill)
              (.setColor g (colour fill))
              (.fill g shape))
            (when-not (= "none" stroke)
              (.setColor g (colour stroke))
              (.setStroke g (BasicStroke. (float stroke-width)))
              (.draw g shape)))
    :text (do (.setColor g (colour fill))
              (.setFont g (Font. ^String font Font/PLAIN (int size)))
              (.drawString g ^String text (int x) (int y)))
    :line (let [path (Path2D$Double.)
                [[x0 y0] & more] points]
            (.moveTo path (double x0) (double y0))
            (doseq [[px py] more] (.lineTo path (double px) (double py)))
            (.setColor g (colour stroke))
            ;; butt caps and mitred joins: what SVG draws a polyline with unless told otherwise
            (.setStroke g (BasicStroke. (float stroke-width) BasicStroke/CAP_BUTT BasicStroke/JOIN_MITER))
            (.draw g path))))

(defn ^{:malli/schema [:=> [:cat :Drawing] :any]}
  png
  "`drawing` as the bytes of a PNG, one pixel to a unit of the drawing: the same marks in the
   same order as `svg` writes them. A selection is not drawn — an image has no viewer. Throws
   what the platform's image encoder throws."
  [{:keys [width height background boxes edges legend]}]
  (when-not (System/getProperty "java.awt.headless")
    ;; painting to memory needs no display, and without this a desktop JVM opens one
    (System/setProperty "java.awt.headless" "true"))
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        g     (.createGraphics image)]
    (try
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setColor g (colour background))
      (.fillRect g 0 0 width height)
      (doseq [mark (concat (mapcat :marks boxes) (mapcat :marks edges) legend)]
        (paint-mark g mark))
      (finally (.dispose g)))
    (let [out (ByteArrayOutputStream.)]
      (when-not (ImageIO/write image "png" out)
        (throw (ex-info "this platform has no PNG encoder" {})))
      (.toByteArray out))))
