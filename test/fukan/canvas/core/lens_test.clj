(ns fukan.canvas.core.lens-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [canvas.vocab.lens :refer [Lens]]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Widget
  "A fixture structure with a relation slot, so a lens query can traverse it."
  (slot :links (many Widget)))

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(defn- names [db eids]
  (set (map #(:entity/name (d/entity db %)) eids)))

(deftest evaluates-a-lens-selection-query-to-its-focus-node-set
  (testing "a lens's single datalog query (over the vocab rules) yields the focus nodes"
    (let [db (s/with-structures
               (s/within-module "m"
                 (Widget "x" (links y z)) (Widget "y") (Widget "z"))
               (s/within-module "other" (Widget "q"))
               (s/within-module "lenses"
                 (Lens "in-m"    (focus "widgets in m" '[(Widget ?n) (in-module ?n "m")]))
                 (Lens "x-links" (focus "what x links to" '[(named ?root "x") (links ?root ?n)]))))]
      (is (= #{"x" "y" "z"} (names db (lens/evaluate-lens db (by-name db "in-m"))))
          "kind + module selection, at domain altitude")
      (is (= #{"y" "z"} (names db (lens/evaluate-lens db (by-name db "x-links"))))
          "relation traversal — scope is just datalog in the one query, no second knob"))))

(deftest prose-only-lens-is-not-evaluable
  (testing "a lens with no selection query throws (prose focus alone isn't evaluable)"
    (let [db (s/with-structures
               (s/within-module "lenses" (Lens "prose" (focus "just words"))))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lens/evaluate-lens db (by-name db "prose")))))))
