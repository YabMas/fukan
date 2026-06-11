(ns fukan.canvas.core.laws-test
  "Law combinators: each shape's positive + negative case, including the case the
   combinators exist to encapsulate — negation over a WHOLLY-EMPTY relation (the
   datascript inline-not-join gotcha), which must still fire."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defn- laws [db] (set (map :law (s/check db))))

;; ── fixtures ──────────────────────────────────────────────────────────────────

(defstructure LDoc
  "matched-by subject: every flagged doc must be approved by a Reviewer."
  {:flag [:? :Bool]}
  (law "every flagged doc is approved by a reviewer"
    (matched-by :approves :from LReviewer :when {:flag true})))

(defstructure LReviewer "Its approvals count."     {:approves [:* LDoc]})
(defstructure LBot      "Its approvals do NOT."    {:approves [:* LDoc]})

(defstructure LRef
  "has subject: a \"ref\"-kinded instance must carry :names."
  {:kind  :String
   :names [:? LDoc]}
  (law "a ref names a target" (has :names :when {:kind "ref"})))

(defstructure LProj
  "has-any subject: maps or wraps — never neither."
  {:maps  [:* LDoc]
   :wraps [:? LProj]}
  (law "a proj maps or wraps" (has-any :maps :wraps)))

(defstructure LSrc "target-condition target." {:polarity :String})
(defstructure LLift
  "target subject: may only lift code-up sources."
  {:lifts LSrc}
  (law "lifts only code-up" (target :lifts {:polarity "code-up"})))

(defstructure LOwned
  "at-most-one subject: a unique owner."
  (law "at most one owner" (at-most-one :owns)))
(defstructure LOwner "owner" {:owns [:* LOwned]})

;; ── matched-by ────────────────────────────────────────────────────────────────

(def mb-orphan (LDoc "orphan" (flag true)))   ; flagged, NO approvals anywhere

(def mb-ok-doc (LDoc "ok" (flag true)))
(def mb-rev    (LReviewer "rev" (approves mb-ok-doc)))

(def mb-bot-doc (LDoc "botted" (flag true)))
(def mb-bot     (LBot "bot" (approves mb-bot-doc)))

(def mb-plain  (LDoc "plain"))                ; unflagged, unapproved — fine

(deftest matched-by-fires-on-the-wholly-empty-relation
  (testing "THE gotcha case: no :approves relation exists at all — must still fire"
    (is (contains? (laws (a/assemble-vars [#'mb-orphan]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-satisfied-by-the-right-counterpart
  (is (not (contains? (laws (a/assemble-vars [#'mb-ok-doc #'mb-rev]))
                      "every flagged doc is approved by a reviewer"))))

(deftest matched-by-from-filters-the-counterpart-kind
  (testing "an approval from a Bot does not satisfy :from LReviewer"
    (is (contains? (laws (a/assemble-vars [#'mb-bot-doc #'mb-bot]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-when-filters-the-subjects
  (is (not (contains? (laws (a/assemble-vars [#'mb-plain]))
                      "every flagged doc is approved by a reviewer"))))

;; ── has ───────────────────────────────────────────────────────────────────────

(def h-bare  (LRef "bare"  (kind "ref")))                  ; ref without names
(def h-named-t (LDoc "t"))
(def h-named (LRef "named" (kind "ref") (names h-named-t)))
(def h-plain (LRef "plain" (kind "plain")))                ; not a ref — exempt

(deftest has-fires-when-the-relation-is-absent
  (is (contains? (laws (a/assemble-vars [#'h-bare])) "a ref names a target")))

(deftest has-satisfied-and-when-scoped
  (let [db (a/assemble-vars [#'h-named #'h-named-t #'h-plain])]
    (is (not (contains? (laws db) "a ref names a target")))))

;; ── has-any ───────────────────────────────────────────────────────────────────

(def ha-neither (LProj "neither"))
(def ha-doc     (LDoc "d"))
(def ha-mapper  (LProj "mapper" (maps ha-doc)))
(def ha-wrapper (LProj "wrapper" (wraps ha-mapper)))

(deftest has-any-fires-only-when-every-alternative-is-absent
  (is (contains? (laws (a/assemble-vars [#'ha-neither])) "a proj maps or wraps"))
  (let [db (a/assemble-vars [#'ha-doc #'ha-mapper #'ha-wrapper])]
    (is (not (contains? (laws db) "a proj maps or wraps")))))

;; ── target ────────────────────────────────────────────────────────────────────

(def t-up   (LSrc "up"   (polarity "code-up")))
(def t-down (LSrc "down" (polarity "design-down")))
(def t-good (LLift "good" (lifts t-up)))
(def t-bad  (LLift "bad"  (lifts t-down)))

(deftest target-checks-the-relations-targets
  (is (not (contains? (laws (a/assemble-vars [#'t-up #'t-good])) "lifts only code-up")))
  (is (contains? (laws (a/assemble-vars [#'t-down #'t-bad])) "lifts only code-up")))

;; ── at-most-one ───────────────────────────────────────────────────────────────

(def amo-x  (LOwned "x"))
(def amo-a  (LOwner "a" (owns amo-x)))
(def amo-b  (LOwner "b" (owns amo-x)))

(def amo-y  (LOwned "y"))
(def amo-c  (LOwner "c" (owns amo-y)))

(deftest at-most-one-fires-on-a-second-incoming
  (is (contains? (laws (a/assemble-vars [#'amo-x #'amo-a #'amo-b])) "at most one owner"))
  (is (not (contains? (laws (a/assemble-vars [#'amo-y #'amo-c])) "at most one owner"))))

;; ── surface errors ────────────────────────────────────────────────────────────

(deftest unknown-combinator-is-rejected-at-expansion
  (let [msg (try (let [_ (macroexpand
                          '(fukan.canvas.core.structure/defstructure BadComb "d"
                             (law "nope" (frobnicate :x))))]
                   "no throw")
                 (catch Throwable e
                   (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
    (is (re-find #"unknown law combinator" msg))))

(deftest combinator-laws-keep-their-authored-src
  (testing "the authored combinator form survives parsing (the print-dual renders it)"
    (let [law (first (:laws (s/structure-by-tag ::LDoc)))]
      (is (= '(matched-by :approves :from LReviewer :when {:flag true}) (:src law))))))
