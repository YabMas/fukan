(ns fukan.canvas.core.lens-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.canvas.core.lens :as lens :refer [Lens Check Projection]]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Widget
  "A fixture structure with a relation slot, so a lens query can traverse it."
  {:links [:* Widget]})

(defstructure Grp
  "A grouping fixture — :child relations place members in a module (in-module)."
  {:child [:* {:contains true} Any]})

(defn- by-name [db n]
  (ffirst (cq/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(defn- names [db eids]
  (set (map #(:entity/name (cq/entity db %)) eids)))

;; module m: x links to y and z; module other: q
(declare w-y w-z)
(Widget ^{:name "x"} w-x {:links [w-y w-z]})
(Widget ^{:name "y"} w-y)
(Widget ^{:name "z"} w-z)
(Grp ^{:name "m"} w-m {:child [w-x w-y w-z]})
(Widget ^{:name "q"} w-q)
(Grp ^{:name "other"} w-other {:child [w-q]})

;; each lens carries its own selection (model-native datalog — no realization shim)
(Lens ^{:name "in-m"}    lns-in-m    "widgets in m"
  {:select '[(Widget ?n) (in-module ?n "m")]})
(Lens ^{:name "x-links"} lns-x-links "what x links to"
  {:select '[(named ?root "x") (links ?root ?n)]})
(Lens ^{:name "prose"}   lns-prose   "just words")
(Lens ^{:name "none"}    lns-none    "widgets in a module that doesn't exist"
  {:select '[(Widget ?n) (in-module ?n "nope")]})

;; a Check gates a lens — a non-empty focus is a violation
(Check ^{:name "widgets-in-m"} chk-fires  {:gates lns-in-m :verdict "widgets exist in m"})
(Check ^{:name "none-in-nope"} chk-passes {:gates lns-none :verdict "no widgets in module nope"})

(deftest evaluates-a-lens-selection-query-to-its-focus-node-set
  (testing "a lens's own selection query (over the vocab rules) yields the focus nodes"
    (let [db (build/vars->cozo [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other
                               #'lns-in-m #'lns-x-links])]
      (is (= #{"x" "y" "z"} (names db (lens/evaluate-lens db (by-name db "in-m"))))
          "kind + module selection, at domain altitude")
      (is (= #{"y" "z"} (names db (lens/evaluate-lens db (by-name db "x-links"))))
          "relation traversal — scope is just datalog in the one query, no second knob"))))

(deftest refine-narrows-a-focus-to-members-also-matching-clauses
  (testing "refine intersects a focus with a further query — lens-within-lens; acts chain over it"
    (let [db    (build/vars->cozo [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other])
          all   (lens/focus-nodes db '[(Widget ?n)])                 ; x y z q
          in-m  (lens/refine db all '[(in-module ?n "m")])]          ; narrow to module m
      (is (= #{"x" "y" "z" "q"} (names db all)))
      (is (= #{"x" "y" "z"} (names db in-m)) "refined to module m")
      ;; chaining: a focus refined again narrows further (here: x's links, then those in m)
      (is (= #{"y" "z"} (names db (lens/refine db (lens/focus-nodes db '[(named ?r "x") (links ?r ?n)])
                                               '[(in-module ?n "m")])))
          "refine composes — a focus narrowed step by step"))))

(deftest prose-only-lens-is-not-evaluable
  (testing "a prose-only lens (no selection query) yields nil — TOTAL, not a throw"
    (let [db (build/vars->cozo [#'lns-prose])]
      (is (nil? (lens/evaluate-lens db (by-name db "prose")))))))

(deftest run-checks-fires-on-a-nonempty-gated-focus
  (testing "a Check gating a lens with a NON-EMPTY focus is a violation (offenders = the focus); a
            gated EMPTY focus passes — the use-side dual of structure/check"
    (let [db         (build/vars->cozo [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other
                                       #'lns-in-m #'lns-none #'chk-fires #'chk-passes])
          violations (lens/run-checks db)
          by-check   (into {} (map (juxt :check identity)) violations)]
      (is (= #{"widgets-in-m"} (set (map :check violations)))
          "only the check whose gated focus is non-empty fires")
      (is (= "widgets exist in m" (:verdict (by-check "widgets-in-m"))))
      (is (= #{"x" "y" "z"} (names db (:offenders (by-check "widgets-in-m"))))
          "the offenders are the gated lens's focus"))))

;; ── projection-focus: the three-way focus resolution ─────────────────────────
;; a projection declares its focus one of three ways: an inline :select, a :through
;; lens (a NAMED shared focus), or neither — no narrowing = the whole model.
(Projection ^{:name "pf-inline"} pf-inline
  "inline focus: the projection carries its own selection"
  {:select '[(Widget ?n) (in-module ?n "m")]})
(Projection ^{:name "pf-through"} pf-through
  "named focus: renders through a shared lens"
  {:through lns-in-m})
(Projection ^{:name "pf-whole"} pf-whole
  "no focus: the whole model")
(Projection ^{:name "pf-prose"} pf-prose
  "through a prose-only lens: not evaluable"
  {:through lns-prose})
(Projection ^{:name "pf-both"} pf-both
  "ILLEGAL: both an inline :select and a :through lens — the never-both law's offender"
  {:select  '[(Widget ?n)]
   :through lns-in-m})

(deftest projection-focus-resolves-three-ways
  (testing "inline :select / :through lens / no focus (whole model); prose-only :through stays nil"
    (let [db (build/vars->cozo [#'w-x #'w-y #'w-z #'w-m #'w-q #'w-other
                               #'lns-in-m #'lns-prose
                               #'pf-inline #'pf-through #'pf-whole #'pf-prose])]
      (is (= #{"x" "y" "z"} (names db (lens/projection-focus db (by-name db "pf-inline"))))
          "inline :select is the projection's own focus")
      (is (= #{"x" "y" "z"} (names db (lens/projection-focus db (by-name db "pf-through"))))
          ":through delegates to the named lens")
      (is (contains? (names db (lens/projection-focus db (by-name db "pf-whole"))) "q")
          "no focus = the whole model (q is outside module m, still in focus)")
      (is (nil? (lens/projection-focus db (by-name db "pf-prose")))
          "a prose-only :through lens is not evaluable — nil, the caller's concern"))))

(deftest a-projection-cannot-carry-both-focus-sources
  (testing "the never-both law fires on a projection with :select AND :through"
    (let [db   (build/vars->cozo [#'lns-in-m #'pf-both])
          offs (->> (law/check db)
                    (filter #(re-find #"never both" (:law %)))
                    (mapcat :offenders) (map first)
                    (map #(:entity/name (cq/entity db %))) set)]
      (is (= #{"pf-both"} offs)))))
