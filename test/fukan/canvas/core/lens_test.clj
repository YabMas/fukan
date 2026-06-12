(ns fukan.canvas.core.lens-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [canvas.acts :refer [Lens]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Widget
  "A fixture structure with a relation slot, so a lens query can traverse it."
  {:links [:* Widget]})

(defstructure Grp
  "A grouping fixture — :child relations place members in a module (in-module)."
  {:child [:* Any]})

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(defn- names [db eids]
  (set (map #(:entity/name (d/entity db %)) eids)))

;; module m: x links to y and z; module other: q
(declare w-y w-z)
(Widget ^{:name "x"} w-x {:links [w-y w-z]})
(Widget ^{:name "y"} w-y)
(Widget ^{:name "z"} w-z)
(Grp ^{:name "m"} w-m {:child [w-x w-y w-z]})
(Widget ^{:name "q"} w-q)
(Grp ^{:name "other"} w-other {:child [w-q]})

;; each lens carries its own selection (model-native datalog — no realization shim)
(Lens ^{:name "in-m"}    lns-in-m    {:focus  "widgets in m"
                                      :select ["widgets in m" '[(Widget ?n) (in-module ?n "m")]]})
(Lens ^{:name "x-links"} lns-x-links {:focus  "what x links to"
                                      :select ["x's links" '[(named ?root "x") (links ?root ?n)]]})
(Lens ^{:name "prose"}   lns-prose   {:focus "just words"})

(deftest evaluates-a-lens-selection-query-to-its-focus-node-set
  (testing "a lens's own selection query (over the vocab rules) yields the focus nodes"
    (let [db (a/assemble-vars [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other
                               #'lns-in-m #'lns-x-links])]
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
