(ns fukan.canvas.projection.view-test
  "The VIEW projection — what is visible, what stands for what is not, and that none of it
   depends on a vocabulary this code knows.

   The fixture is its own two-sort vocabulary on purpose. A view names relations and nothing
   else, so the test that it draws a vocabulary fukan does not ship is that these tests pass
   without one of fukan's sorts in them."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :refer [defrelation defstructure]]
            [fukan.canvas.projection.view :as view]
            [fukan.cozo.build :as build]
            [fukan.cozo.query]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]))

(declare VCrate)

(defstructure VPart
  "A part. `:vneeds` is the relation drawn as an edge."
  {:vneeds [:* VPart]})

(defstructure VCrate
  "A crate of parts and other crates. `:vholds` is the relation that nests."
  {:vholds  [:* VCrate VPart]
   :vshares [:* VPart]})

(defrelation :vneeded
  "A part some other part needs — a property of ONE node, so nothing a view can nest or link by."
  [?p]
  [(VPart ?p) (vneeds ?q ?p)])

(VPart bolt)
(VPart nut   {:vneeds [bolt]})
(VPart gear  {:vneeds [bolt nut]})
(VPart loose {:vneeds [gear]})                    ; held by no crate and holding nothing: unplaced
(VCrate inner {:vholds [gear]})
(VCrate left  {:vholds [bolt nut]})
(VCrate right {:vholds [inner] :vshares [bolt]})
(VCrate twin  {:vholds [bolt]})                   ; bolt has two containers

(def ^:private fixture-vars [#'bolt #'nut #'gear #'loose #'inner #'left #'right #'twin])

(defn- db [] (build/with-grammar (build/vars->cozo fixture-vars) nil))

(def ^:private spec {:nest ["vholds"] :link ["vneeds"]})

(defn- id [v] (str (ns-name (:ns (meta v))) "/" (:name (meta v))))

(defn- labels [graph] (set (map :label (:nodes graph))))

(defn- edge-set [graph]
  (set (map (fn [{:keys [from to count]}] [(peek from) (peek to) count]) (:edges graph))))

(deftest a-fresh-view-is-the-root-level
  (let [g (view/view-graph (db) spec #{})]
    (is (= #{"left" "right" "twin"} (labels g))
        "the roots are the nodes nothing nests; a held node does not appear")
    (is (every? (complement :open) (:nodes g)))
    (is (= {"left" 2 "right" 1 "twin" 1}
           (into {} (map (juxt :label :members)) (:nodes g))))))

(deftest opening-a-path-shows-its-direct-members-only
  (let [g (view/view-graph (db) spec #{[(id #'right)]})]
    (is (contains? (labels g) "inner"))
    (is (not (contains? (labels g) "gear"))
        "gear is inner's member, and inner was not opened")
    (is (:open (first (filter #(= "right" (:label %)) (:nodes g)))))))

(deftest a-node-with-two-containers-appears-under-each
  (let [g     (view/view-graph (db) spec #{[(id #'left)] [(id #'twin)]})
        bolts (filter #(= "bolt" (:label %)) (:nodes g))]
    (is (= #{[(id #'left) (id #'bolt)] [(id #'twin) (id #'bolt)]}
           (set (map :path bolts))))))

(deftest a-hidden-end-is-carried-by-the-closed-box-around-it
  (testing "everything closed: each edge lands on the root holding its end, aggregated"
    (is (= #{;; gear→bolt and gear→nut both leave `right` for `left`
             [(id #'right) (id #'left) 2]
             ;; gear→bolt also reaches bolt's other container
             [(id #'right) (id #'twin) 1]
             ;; nut→bolt: nut is in left, bolt is also in twin
             [(id #'left) (id #'twin) 1]}
           (edge-set (view/view-graph (db) spec #{})))
        "nut→bolt inside `left` is internal to a closed box and is not drawn"))
  (testing "opening a box moves its edges onto what became visible"
    (let [es (edge-set (view/view-graph (db) spec #{[(id #'left)]}))]
      (is (contains? es [(id #'nut) (id #'bolt) 1])
          "the edge that was internal to `left` is drawn once both ends are visible")
      (is (contains? es [(id #'right) (id #'bolt) 1]))
      (is (contains? es [(id #'right) (id #'nut) 1]))
      (is (not-any? (fn [[_ to _]] (= to (id #'left))) es)
          "an open box no longer stands for its members"))))

(deftest a-node-the-nesting-says-nothing-about-is-counted-not-drawn
  (let [sort  "fukan.canvas.projection.view-test/VPart"
        fresh (view/view-graph (db) spec #{})
        asked (view/view-graph (db) (assoc spec :unplaced true) #{})]
    (is (= [{:sort sort :count 1}] (:unplaced fresh)))
    (is (not (contains? (labels fresh) "loose")))
    (is (contains? (labels asked) "loose") "a spec that asks for them gets them at the root level")
    (is (contains? (edge-set asked) [(id #'loose) (id #'right) 1]))
    (is (= (:unplaced fresh) (:unplaced asked))
        "the count is a reading of the model, the same whether or not they are drawn"))
  (testing "placement is judged over the whole design, not the selection"
    (let [g (view/view-graph (db) (assoc spec :select '[(VCrate ?n) (named ?n "twin")]) #{})]
      (is (= #{"twin"} (labels g)) "twin holds only a VPart, which the selection drops")
      (is (empty? (:unplaced g))))))

(deftest a-rooted-view-starts-at-the-root-s-members
  (let [g (view/view-graph (db) (assoc spec :root (id #'right)) #{})]
    (is (= #{[(id #'inner)]} (set (map :path (:nodes g))))
        "the members are the root level, and their paths start at themselves")
    (is (empty? (:edges g)) "what inner's gear needs lies outside the root and is not drawn")
    (is (empty? (:unplaced g))))
  (is (= #{"bolt" "nut"}
         (labels (view/view-graph (db) (assoc spec :root (id #'left) :select '[(VPart ?n)]) #{})))
      "the root itself need not be in the selection")
  (is (empty? (:nodes (view/view-graph (db) (assoc spec :root "no.such/node") #{})))))

(deftest only-the-spec-s-relations-are-drawn
  (is (empty? (:edges (view/view-graph (db) {:nest ["vholds"] :link []} #{}))))
  (is (= #{[(id #'right) (id #'left) 1] [(id #'right) (id #'twin) 1]}
         (edge-set (view/view-graph (db) {:nest ["vholds"] :link ["vshares"]} #{})))
      "a relation the spec does not name contributes nothing; one it names is drawn like any other"))

(deftest a-path-that-is-not-visible-is-ignored
  (let [fresh (view/view-graph (db) spec #{})]
    (is (= fresh (view/view-graph (db) spec #{["no.such/node"]})))
    (is (= fresh (view/view-graph (db) spec #{[(id #'right) (id #'inner)]}))
        "inner is open in the address, but its container is closed, so it is not reached")))

(deftest a-selection-narrows-what-is-drawn
  (let [g (view/view-graph (db) (assoc spec :select '[(VCrate ?n)]) #{[(id #'right)]})]
    (is (= #{"left" "right" "twin" "inner"} (labels g)))
    (is (empty? (:edges g)) "an edge to a node outside the selection is not drawn")))

(deftest extracted-nodes-are-not-part-of-the-design
  (let [fact-db (build/with-grammar
                  (build/fact-vars->cozo [#'left #'bolt #'nut] [#'loose #'gear])
                  nil)
        g       (view/view-graph fact-db spec #{[(id #'left)]})]
    (is (= #{"left" "bolt" "nut"} (labels g)))
    (is (= #{[(id #'nut) (id #'bolt) 1]} (edge-set g))
        "gear→bolt and loose→gear end at extracted nodes and are not drawn")))

(deftest views-offers-the-relations-the-grammar-declares
  (let [rels (into {} (map (juxt :name :edges)) (:relations (view/views (db))))]
    (is (= 5 (get rels "vholds")))
    (is (= 4 (get rels "vneeds")))
    (is (= 1 (get rels "vshares")))
    (is (seq (fukan.cozo.query/q '[:find ?r :where [?r :structure/of :fukan.canvas.core.reflect/Relation]
                                   [?r :entity/name "vneeded"]]
                                 (db)))
        "the grammar does declare it")
    (is (not (contains? rels "vneeded"))
        "a relation that does not relate two things is not offered")))
