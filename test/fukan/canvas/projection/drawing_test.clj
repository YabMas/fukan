(ns fukan.canvas.projection.drawing-test
  "The DRAWING projection, held to what it claims: a Drawing accounts for every occurrence and
   edge of its graph, is a function of that graph alone, and is written whole by both encoders.

   The graphs are written out by hand — `draw` takes a view graph and no model, and that it needs
   none is part of what is being tested."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.projection.drawing :as drawing])
  (:import [java.io ByteArrayInputStream]
           [javax.imageio ImageIO]))

(def ^:private graph
  "A container opened onto two members, a closed sibling, and edges at both depths."
  {:nodes [{:path ["v/shelf"] :sort "v/Shelf" :label "shelf" :members 2 :open true}
           {:path ["v/shelf" "v/a"] :sort "v/Book" :label "a <first>" :members 0 :open false}
           {:path ["v/shelf" "v/b"] :sort "v/Book" :label "b" :members 0 :open false}
           {:path ["v/table"] :sort "v/Shelf" :label "table" :members 3 :open false}]
   :edges [{:from ["v/shelf" "v/a"] :to ["v/shelf" "v/b"] :relation "cites" :count 1}
           {:from ["v/shelf" "v/b"] :to ["v/table"] :relation "rests-on" :count 5}]
   :unplaced []})

(defn- marks-of [drawing]
  (concat (mapcat :marks (:boxes drawing)) (mapcat :marks (:edges drawing)) (:legend drawing)))

(defn- outline
  "A box's own rectangle as `[x y width height]`."
  [box]
  (some (fn [[kind m]] (when (and (= :rect kind) (= "box" (:role m))) ((juxt :x :y :width :height) m)))
        (:marks box)))

(deftest a-drawing-accounts-for-its-whole-graph
  (let [d     (drawing/draw graph "light")
        boxes (into {} (map (juxt :path identity)) (:boxes d))]
    (testing "a box for every occurrence, carrying the node's label as text"
      (is (= (map :path (:nodes graph)) (map :path (:boxes d))))
      (doseq [{:keys [path label]} (:nodes graph)]
        (is (some (fn [[kind m]] (and (= :text kind) (= label (:text m)))) (:marks (boxes path)))
            (str label " is written in its box"))))
    (testing "a member lies inside the box of the occurrence that contains it"
      (let [[px py pw ph] (outline (boxes ["v/shelf"]))]
        (doseq [member [["v/shelf" "v/a"] ["v/shelf" "v/b"]]
                :let [[x y w h] (outline (boxes member))]]
          (is (and (<= px x) (<= py y) (<= (+ x w) (+ px pw)) (<= (+ y h) (+ py ph)))))))
    (testing "marks for every edge, between the boxes it joins"
      (is (= (map #(select-keys % [:from :to :relation :count]) (:edges graph))
             (map #(select-keys % [:from :to :relation :count]) (:edges d))))
      (is (every? (comp seq :marks) (:edges d))))
    (testing "everything is inside the drawing, in whole numbers"
      (doseq [[kind m] (marks-of d)
              [x y]    (case kind
                         :rect [[(:x m) (:y m)] [(+ (:x m) (:width m)) (+ (:y m) (:height m))]]
                         :text [[(:x m) (:y m)]]
                         :line (:points m))]
        (is (and (int? x) (int? y) (<= 0 x (:width d)) (<= 0 y (:height d))))))))

(deftest every-mark-lies-inside-the-size-the-drawing-states
  (testing "a legend entry wider than the whole graph, a long sort name, and an edge count are all inside"
    (let [rel (apply str "relation-with-a-name-far-wider-than-anything-the-graph-draws-" (repeat 4 "and-more-"))
          d   (drawing/draw {:nodes [{:path ["v/a"] :sort (str "v/" (apply str (repeat 12 "Sort"))) :label "a" :members 0 :open false}
                                     {:path ["v/b"] :sort "v/B" :label "b" :members 0 :open false}]
                             :edges [{:from ["v/a"] :to ["v/b"] :relation rel :count 999999}]
                             :unplaced []}
                            "light")]
      (doseq [[kind m] (marks-of d)
              :let [[x0 y0 x1 y1]
                    (case kind
                      :rect [(:x m) (:y m) (+ (:x m) (:width m)) (+ (:y m) (:height m))]
                      :text [(:x m) (:y m) (+ (:x m) (long (Math/ceil (* 0.6 (:size m) (count (:text m)))))) (:y m)]
                      :line (let [xs (map first (:points m)) ys (map second (:points m))]
                              [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]))]]
        (is (and (<= 0 x0 x1 (:width d)) (<= 0 y0 y1 (:height d)))
            (str (pr-str [kind (or (:text m) (:role m))]) " lies inside " [(:width d) (:height d)]))))))

(deftest a-box-is-as-wide-as-its-own-label-needs
  (testing "a container holding one small box is still wide enough for its own long label"
    (let [long-label "a-container-with-a-much-longer-name-than-what-it-holds"
          d (drawing/draw {:nodes [{:path ["v/outer"] :sort "v/Shelf" :label "outermost-of-them-all-by-a-distance-in-characters-too" :members 1 :open true}
                                   {:path ["v/outer" "v/c"] :sort "v/Shelf" :label long-label :members 1 :open true}
                                   {:path ["v/outer" "v/c" "v/x"] :sort "v/Book" :label "x" :members 0 :open false}]
                           :edges [] :unplaced []}
                          "light")]
      (doseq [box (:boxes d)
              :let [[x _ w _] (outline box)]
              [kind m] (:marks box)
              :when (= :text kind)]
        (is (<= (+ (:x m) (long (Math/ceil (* 0.6 (:size m) (count (:text m)))))) (+ x w))
            (str (pr-str (:text m)) " ends inside its box"))))))

(deftest a-drawing-is-a-function-of-its-graph
  (is (= (drawing/draw graph "light") (drawing/draw graph "light")))
  (testing "a palette changes paint and never position"
    (let [geometry (fn [d] (for [[kind m] (marks-of d)] [kind (dissoc m :fill :stroke)]))
          light    (drawing/draw graph "light")
          dark     (drawing/draw graph "dark")]
      (is (= (geometry light) (geometry dark)))
      (is (not= (:background light) (:background dark)))))
  (testing "a colour comes from a name, so a sort keeps its colour between two drawings"
    (let [stroke (fn [g path] (some (fn [[_ m]] (when (= "box" (:role m)) (:stroke m)))
                                    (:marks (first (filter #(= path (:path %)) (:boxes (drawing/draw g "light")))))))]
      (is (= (stroke graph ["v/table"])
             (stroke (assoc graph :nodes (subvec (:nodes graph) 3) :edges []) ["v/table"])))))
  (testing "a graph with nothing in it is still a drawing"
    (let [d (drawing/draw {:nodes [] :edges [] :unplaced []} "light")]
      (is (and (pos? (:width d)) (pos? (:height d)) (empty? (:boxes d)))))))

(deftest both-encoders-write-every-mark
  (let [d     (drawing/draw graph "light")
        marks (marks-of d)
        text  (drawing/svg d)
        n     (fn [kind] (count (filter #(= kind (first %)) marks)))]
    (testing "the SVG"
      (is (= (inc (n :rect)) (count (re-seq #"<rect" text))) "every rectangle, and the ground")
      (is (= (n :text) (count (re-seq #"<text" text))))
      (is (= (n :line) (count (re-seq #"<polyline" text))))
      (is (= (map :title (:edges d))
             (map #(-> (second %) (str/replace "&gt;" ">") (str/replace "&lt;" "<") (str/replace "&amp;" "&"))
                  (re-seq #"<title>([^<]*)</title>" text)))
          "an edge's caption is the Drawing's, written as stated")
      (is (str/includes? text "a &lt;first&gt;") "text is escaped, not interpreted")
      (is (str/includes? text "data-path=\"[&quot;v/shelf&quot;,&quot;v/a&quot;]\"")
          "a box's group says which occurrence it is")
      (is (str/includes? text (str "stroke:" (:stroke (:selected d))))
          "the selected outline is the Drawing's"))
    (testing "every paint value the SVG holds is one the Drawing states"
      (let [stated (into #{"none" (:background d) (:stroke (:selected d))}
                         (mapcat (fn [[_ m]] [(:fill m) (:stroke m)])) marks)]
        (is (every? stated (map second (re-seq #"(?:fill|stroke)=\"([^\"]+)\"" text))))))
    (testing "the PNG is an image of the drawing's size"
      (let [image (ImageIO/read (ByteArrayInputStream. (drawing/png d)))]
        (is (= [(:width d) (:height d)] [(.getWidth image) (.getHeight image)]))
        (is (not= (.getRGB image 0 0)
                  (let [[x y] (outline (first (:boxes d)))] (.getRGB image (+ x 20) (+ y 36))))
            "something is painted over the ground")))))
