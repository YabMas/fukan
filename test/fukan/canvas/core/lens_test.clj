(ns fukan.canvas.core.lens-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [canvas.essence.lens :refer [Lens]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Widget
  "A fixture structure with a relation slot, so a lens query can traverse it."
  (slot :links (many Widget)))

(defstructure Grp
  "A grouping fixture — :child relations place members in a module (in-module)."
  (slot :child (many Any)))

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(defn- names [db eids]
  (set (map #(:entity/name (d/entity db %)) eids)))

;; module m: x links to y and z; module other: q
(declare w-y w-z)
(def w-x     (Widget "x" (links w-y w-z)))
(def w-y     (Widget "y"))
(def w-z     (Widget "z"))
(def w-m     (Grp "m" (child w-x w-y w-z)))
(def w-q     (Widget "q"))
(def w-other (Grp "other" (child w-q)))

(def lns-in-m    (Lens "in-m"    (focus "widgets in m" '[(Widget ?n) (in-module ?n "m")])))
(def lns-x-links (Lens "x-links" (focus "what x links to" '[(named ?root "x") (links ?root ?n)])))
(def lns-prose   (Lens "prose"   (focus "just words")))

(deftest evaluates-a-lens-selection-query-to-its-focus-node-set
  (testing "a lens's single datalog query (over the vocab rules) yields the focus nodes"
    (let [db (a/assemble-vars [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other #'lns-in-m #'lns-x-links])]
      (is (= #{"x" "y" "z"} (names db (lens/evaluate-lens db (by-name db "in-m"))))
          "kind + module selection, at domain altitude")
      (is (= #{"y" "z"} (names db (lens/evaluate-lens db (by-name db "x-links"))))
          "relation traversal — scope is just datalog in the one query, no second knob"))))

(deftest refine-narrows-a-focus-to-members-also-matching-clauses
  (testing "refine intersects a focus with a further query — lens-within-lens; acts chain over it"
    (let [db    (a/assemble-vars [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other])
          all   (lens/focus-nodes db '[(Widget ?n)])                 ; x y z q
          in-m  (lens/refine db all '[(in-module ?n "m")])]          ; narrow to module m
      (is (= #{"x" "y" "z" "q"} (names db all)))
      (is (= #{"x" "y" "z"} (names db in-m)) "refined to module m")
      ;; chaining: a focus refined again narrows further (here: x's links, then those in m)
      (is (= #{"y" "z"} (names db (lens/refine db (lens/focus-nodes db '[(named ?r "x") (links ?r ?n)])
                                               '[(in-module ?n "m")])))
          "refine composes — a focus narrowed step by step"))))

(deftest prose-only-lens-is-not-evaluable
  (testing "a lens with no selection query throws (prose focus alone isn't evaluable)"
    (let [db (a/assemble-vars [#'lns-prose])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lens/evaluate-lens db (by-name db "prose")))))))
