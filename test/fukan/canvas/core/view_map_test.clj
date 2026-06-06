(ns fukan.canvas.core.view-map-test
  "Minimal proof of VIEW-MAPPING (the functorial inter-view morphism — the
   second-operator exploration). Three things on a two-view fixture that mirrors the
   self-model's collab(purpose) → overview(concept) → subsystem(impl) chain:

   1. `traces` composes view-maps across views in one query (the new capability).
   2. a FUNCTORIALITY law surfaces cross-view drift — a step-sequence whose mapped
      faculties don't flow is caught (works TODAY, no new machinery).
   3. authoring `traces` + `view-map` as a LAW's :rules trips `check-law-recursion!`
      — the concrete evidence that `view-map` (a relation-level coproduct) wants to be
      a VOCAB-DERIVED rule, not a law-local one.

   These ride the existing engine (recursive rules + laws); nothing new is built here —
   the point is to see whether the concept lands and to answer free-rule-vs-sugar."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── two-view fixtures ────────────────────────────────────────────────────────
;; IMPL view: a module.   CONCEPT view: a faculty that flows (:feeds) + is realized.
;; PURPOSE view: a step that sequences (:next) and maps to a concept (:via).
(defstructure Mod "impl-view node." (slot :child (many Any)))
(defstructure Fac "concept-view node: flows to peers (:feeds), realized by a module (:realized-by)."
  (slot :feeds (many Fac))
  (slot :realized-by (many Mod)))
(defstructure Step "purpose-view node: sequences (:next), maps to a concept (:via)."
  (slot :next (optional Step))
  (slot :via  (one Fac)))

;; the cross-view links unified as `view-map`, composed transitively as `traces`.
;; (via + realized-by are auto-derived rel-rules; view-map/traces are the new bits.)
(def view-map-rules
  '[[(view-map ?a ?b) (via ?a ?b)]
    [(view-map ?a ?b) (realized-by ?a ?b)]
    [(traces ?a ?b) (view-map ?a ?b)]
    [(traces ?a ?c) (view-map ?a ?b) (traces ?b ?c)]])

;; ── aligned chain: SA→SB,  SA↦FA SB↦FB,  FA feeds FB,  FA realized-by M1 ───────
(def m1 (Mod))
(def fb (Fac))
(def fa (Fac (feeds fb) (realized-by m1)))
(def sb (Step (via fb)))
(def sa (Step (next sb) (via fa)))

(deftest traces-composes-across-views
  (testing "sa (purpose) traces via→fa (concept) →realized-by→m1 (impl): three views, one query"
    (let [db    (a/assemble-vars [#'m1 #'fb #'fa #'sb #'sa])
          rules (into (vec (s/vocab-rules)) view-map-rules)
          reached (set (d/q '[:find [?nm ...] :in $ %
                              :where [?sa :entity/name "sa"] (traces ?sa ?x) [?x :entity/name ?nm]]
                            db rules))]
      (is (= #{"fa" "m1"} reached)
          "feeds is intra-view flow, NOT a view-map, so sa does not trace to fb"))))

;; ── the functoriality law: a view-map must preserve flow ──────────────────────
(defstructure FlowFunctor
  "Auditor: the :via view-map must preserve flow — if SA :next SB then their mapped
   faculties must feed (FA :feeds FB). A step-sequence whose faculties don't flow is
   cross-view drift. Non-recursive; uses only auto-derived rel-rules (next/via/feeds)."
  (law "view-map preserves flow"
    :scope :Step
    :offenders '[?a ?b]
    :where '[(next ?a ?b) (via ?a ?fa) (via ?b ?fb) (not (feeds ?fa ?fb))]))

;; drift chain: SC→SD,  SC↦FC SD↦FD,  but FC does NOT feed FD
(def fc (Fac))
(def fd (Fac))
(def sd (Step (via fd)))
(def sc (Step (next sd) (via fc)))

(defn- flow-offenders [db]
  (->> (s/check db)
       (filter #(= "view-map preserves flow" (:law %)))
       (mapcat :offenders)
       (map (fn [row] (set (map #(:entity/name (d/entity db %)) row))))
       set))

(deftest functoriality-law-catches-cross-view-drift
  (testing "aligned: SA→SB maps to FA feeds FB → no violation"
    (let [db (a/assemble-vars [#'m1 #'fb #'fa #'sb #'sa])]
      (is (empty? (flow-offenders db)))))
  (testing "drift: SC→SD but FC does not feed FD → caught as a violation"
    (let [db (a/assemble-vars [#'fc #'fd #'sd #'sc])]
      (is (= #{#{"sc" "sd"}} (flow-offenders db))))))

;; ── the evidence: traces+view-map as LAW :rules is rejected by the recursion guard
(defn- throws-recursion-guard?
  "True if `thunk` throws (anywhere in its cause chain) the check-law-recursion! error.
   macroexpand of a fully-qualified defstructure routes through the Compiler, which wraps
   the macro's ex-info in a CompilerException — so we walk the cause chain (cf. the same
   wrapping handled in the realized-concept guard test)."
  [thunk]
  (try (thunk) false
       (catch Throwable e
         (boolean (some #(re-find #"recursive rule" (or (.getMessage ^Throwable %) ""))
                        (take-while some? (iterate #(.getCause ^Throwable %) e)))))))

(deftest traces-as-law-rules-trips-recursion-guard
  (testing "a recursive `traces` rule that calls the `view-map` rule is rejected at
            macro-expansion (check-law-recursion!) — so view-map wants to be vocab-derived"
    (is (throws-recursion-guard?
          #(macroexpand
             '(fukan.canvas.core.structure/defstructure BadTrace "fixture"
                (law "trace"
                  :offenders '[?a ?b]
                  :rules '[[(view-map ?a ?b) (via ?a ?b)]
                           [(traces ?a ?b) (view-map ?a ?b)]
                           [(traces ?a ?c) (view-map ?a ?b) (traces ?b ?c)]]
                  :where '[(traces ?a ?b)])))))))
