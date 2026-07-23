(ns fukan.canvas.core.correspond-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s :refer [defstructure defrelation correspond]]))

(defstructure CTDesign "test design sort" {:uses [:* CTDesign]})
(defstructure CTFact   "test fact sort"   {:links [:* CTFact]})
(defrelation :ct-open "test predicate" [?x] [[?x :val/extracted true]])

(correspond [CTDesign ?d CTFact ?f]
  [(named ?d ?n) (named ?f ?n)]
  {:uses [:cat :links [:* [:cat [:not ct-open] :links]]]})

(deftest registration
  (let [c (s/correspond-by-pair [::CTDesign ::CTFact])]
    (is (some? c))
    (is (= '?d (:dvar c)))
    (is (= {:uses '[:cat :links [:* [:cat [:not ct-open] :links]]]} (:map c)))))

(deftest completeness-guard
  ;; a design slot with no entry throws at registration
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no realization entry"
        (s/register-correspond!
         {:design ::CTDesign :fact ::CTFact :dvar '?d :fvar '?f
          :match '[(named ?d ?n) (named ?f ?n)] :map {} :ns (str *ns*)}))))

(deftest zero-admitting-entry-throws
  ;; a realization entry that admits the empty path (the identity) would mint an ungroundable
  ;; reflexive realized-<rel> rule — rejected at registration, before any rule is emitted
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"admits the empty path"
        (s/register-correspond!
         {:design ::CTDesign :fact ::CTFact :dvar '?d :fvar '?f
          :match '[(named ?d ?n) (named ?f ?n)]
          :map {:uses '[:* :links]} :ns (str *ns*)}))))

(deftest cross-ns-collision-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already declared"
        (s/register-correspond!
         {:design ::CTDesign :fact ::CTFact :dvar '?d :fvar '?f
          :match '[(named ?d ?n) (named ?f ?n)]
          :map {:uses :links} :ns "some.other.ns"}))))

(deftest emitted-terms
  (let [rules (s/terms-of (s/all-structures))
        heads (set (map (comp first first) rules))]
    ;; the pairing rule feeds the ambient open head
    (is (contains? heads 'corresponds))
    ;; the entry rule is minted realized-<rel>
    (is (contains? heads 'realized-uses))
    ;; compound closure minted an auxiliary recursive rule
    (is (contains? heads 'realized-uses-s1))))

(deftest pairing-rule-shape
  ;; our pairing rule guards both head sorts ns-precisely
  (let [rules (filter #(= 'corresponds (ffirst %)) (s/terms-of (s/all-structures)))
        ours  (first (filter #(some #{['?d :structure/of ::CTDesign]} (rest %)) rules))]
    (is (some? ours))
    (is (some #{['?f :structure/of ::CTFact]} (rest ours)))))
