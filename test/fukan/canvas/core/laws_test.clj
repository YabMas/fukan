(ns fukan.canvas.core.laws-test
  "Law combinators: each shape's positive + negative case, including negation over a
   WHOLLY-EMPTY relation — the case datascript's inline not-join got wrong (forcing the
   old hand-rolled negation rules) and Cozo's stratified not-join handles directly."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            ;; loaded for its side-effect: registers the Cozo check engine so s/check dispatches to it
            [fukan.cozo.law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defn- laws [db] (set (map :law (s/check db))))

;; ── fixtures ──────────────────────────────────────────────────────────────────

(defstructure LDoc
  "matched-by subject: every flagged doc must be approved by a Reviewer."
  {:flag [:? :boolean]}
  (law "every flagged doc is approved by a reviewer"
    (matched-by :approves :from LReviewer :when {:flag true})))

(defstructure LReviewer "Its approvals count."     {:approves [:* LDoc]})
(defstructure LBot      "Its approvals do NOT."    {:approves [:* LDoc]})

(defstructure LRef
  "has subject: a \"ref\"-kinded instance must carry :names."
  {:kind  :string
   :names [:? LDoc]}
  (law "a ref names a target" (has :names :when {:kind "ref"})))

(defstructure LProj
  "has-any subject: maps or wraps — never neither."
  {:maps  [:* LDoc]
   :wraps [:? LProj]}
  (law "a proj maps or wraps" (has-any :maps :wraps)))

(defstructure LSrc "target-condition target." {:polarity :string})
(defstructure LLift
  "target subject: may only lift code-up sources."
  {:lifts LSrc}
  (law "lifts only code-up" (target :lifts {:polarity "code-up"})))

(defstructure LOwned
  "at-most-one subject: a unique owner."
  (law "at most one owner" (at-most-one :owns)))
(defstructure LOwner "owner" {:owns [:* LOwned]})

;; ── matched-by ────────────────────────────────────────────────────────────────

(LDoc ^{:name "orphan"} mb-orphan {:flag true})   ; flagged, NO approvals anywhere

(LDoc ^{:name "ok"} mb-ok-doc {:flag true})
(LReviewer ^{:name "rev"} mb-rev {:approves [mb-ok-doc]})

(LDoc ^{:name "botted"} mb-bot-doc {:flag true})
(LBot ^{:name "bot"} mb-bot {:approves [mb-bot-doc]})

(LDoc ^{:name "plain"} mb-plain)              ; unflagged, unapproved — fine

(deftest matched-by-fires-on-the-wholly-empty-relation
  (testing "THE gotcha case: no :approves relation exists at all — must still fire"
    (is (contains? (laws (build/vars->cozo [#'mb-orphan]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-satisfied-by-the-right-counterpart
  (is (not (contains? (laws (build/vars->cozo [#'mb-ok-doc #'mb-rev]))
                      "every flagged doc is approved by a reviewer"))))

(deftest matched-by-from-filters-the-counterpart-kind
  (testing "an approval from a Bot does not satisfy :from LReviewer"
    (is (contains? (laws (build/vars->cozo [#'mb-bot-doc #'mb-bot]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-when-filters-the-subjects
  (is (not (contains? (laws (build/vars->cozo [#'mb-plain]))
                      "every flagged doc is approved by a reviewer"))))

;; ── has ───────────────────────────────────────────────────────────────────────

(LRef ^{:name "bare"} h-bare {:kind "ref"})                ; ref without names
(LDoc ^{:name "t"} h-named-t)
(LRef ^{:name "named"} h-named {:kind "ref" :names h-named-t})
(LRef ^{:name "plain"} h-plain {:kind "plain"})            ; not a ref — exempt

(deftest has-fires-when-the-relation-is-absent
  (is (contains? (laws (build/vars->cozo [#'h-bare])) "a ref names a target")))

(deftest has-satisfied-and-when-scoped
  (let [db (build/vars->cozo [#'h-named #'h-named-t #'h-plain])]
    (is (not (contains? (laws db) "a ref names a target")))))

;; ── has-any ───────────────────────────────────────────────────────────────────

(LProj ^{:name "neither"} ha-neither)
(LDoc ^{:name "d"} ha-doc)
(LProj ^{:name "mapper"} ha-mapper {:maps [ha-doc]})
(LProj ^{:name "wrapper"} ha-wrapper {:wraps ha-mapper})

(deftest has-any-fires-only-when-every-alternative-is-absent
  (is (contains? (laws (build/vars->cozo [#'ha-neither])) "a proj maps or wraps"))
  (let [db (build/vars->cozo [#'ha-doc #'ha-mapper #'ha-wrapper])]
    (is (not (contains? (laws db) "a proj maps or wraps")))))

;; ── target ────────────────────────────────────────────────────────────────────

(LSrc ^{:name "up"}   t-up   {:polarity "code-up"})
(LSrc ^{:name "down"} t-down {:polarity "design-down"})
(LLift ^{:name "good"} t-good {:lifts t-up})
(LLift ^{:name "bad"}  t-bad  {:lifts t-down})

(deftest target-checks-the-relations-targets
  (is (not (contains? (laws (build/vars->cozo [#'t-up #'t-good])) "lifts only code-up")))
  (is (contains? (laws (build/vars->cozo [#'t-down #'t-bad])) "lifts only code-up")))

;; ── at-most-one ───────────────────────────────────────────────────────────────

(LOwned ^{:name "x"} amo-x)
(LOwner ^{:name "a"} amo-a {:owns [amo-x]})
(LOwner ^{:name "b"} amo-b {:owns [amo-x]})

(LOwned ^{:name "y"} amo-y)
(LOwner ^{:name "c"} amo-c {:owns [amo-y]})

(deftest at-most-one-fires-on-a-second-incoming
  (is (contains? (laws (build/vars->cozo [#'amo-x #'amo-a #'amo-b])) "at most one owner"))
  (is (not (contains? (laws (build/vars->cozo [#'amo-y #'amo-c])) "at most one owner"))))

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
