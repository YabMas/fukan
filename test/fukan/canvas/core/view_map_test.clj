(ns fukan.canvas.core.view-map-test
  "View-mapping built on a relation-coproduct (the second-operator exploration, now
   realised). On a two-view fixture mirroring the self-model's collab(purpose) →
   overview(concept) → subsystem(impl) chain:

   1. `defrelation-coproduct` makes the cross-view link a VOCAB-DERIVED relation
      (`xview` = :via ∪ :realized-by) — union rules emitted by `derive-rules`.
   2. `traces` composes that coproduct across views in one query.
   3. because `xview` is vocab-derived (not law-local), a recursive `traces` rule is
      now expressible AS A LAW's :rules — the recursion guard no longer trips — so a
      cross-view reachability law runs.
   4. a FUNCTORIALITY law surfaces cross-view drift (works on auto-derived rel-rules).

   The coproduct is the only new kernel mechanic; traces + the laws ride the engine."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── two-view fixtures ────────────────────────────────────────────────────────
;; IMPL view: a module.  CONCEPT view: a faculty that flows (:feeds) + is realized.
;; PURPOSE view: a step that sequences (:next) and maps to a concept (:via).
(defstructure Mod "impl-view node." {:child [:* Any]})
(defstructure Fac "concept-view node: flows to peers (:feeds), realized by a module (:realized-by)."
  {:feeds [:* Fac] :realized-by [:* Mod]})
(defstructure Step "purpose-view node: sequences (:next), maps to a concept (:via)."
  {:next [:? Step] :via Fac})

;; the cross-view link as a relation-coproduct — now a VOCAB-DERIVED relation.
;; (named `xview` to avoid colliding with the real `view-map` in canvas/domain/view.clj,
;;  since the structure registry is a single global tag namespace.)
(s/defrelation-coproduct :xview "test coproduct: the cross-view link relations" :via :realized-by)

;; `traces` = the transitive composition of the coproduct (a local recursive rule).
(def traces-rules
  '[[(traces ?a ?b) (xview ?a ?b)]
    [(traces ?a ?c) (xview ?a ?b) (traces ?b ?c)]])

;; aligned chain: SA→SB,  SA↦FA SB↦FB,  FA feeds FB,  FA realized-by M1
(Mod m1)
(Fac fb)
(Fac fa {:feeds [fb] :realized-by [m1]})
(Step sb {:via fb})
(Step sa {:next sb :via fa})

(deftest coproduct-is-vocab-derived
  (testing "defrelation-coproduct emits the union rules into vocab-rules"
    (let [rs (s/vocab-rules)]
      (is (some #(= % '[(xview ?a ?b) (via ?a ?b)]) rs))
      (is (some #(= % '[(xview ?a ?b) (realized-by ?a ?b)]) rs)))))

(deftest traces-composes-across-views
  (testing "sa traces via→fa →realized-by→m1: three views, one query (xview is vocab-derived)"
    (let [db    (build/vars->cozo [#'m1 #'fb #'fa #'sb #'sa])
          rules (into (vec (s/vocab-rules)) traces-rules)
          reached (set (cq/q '[:find [?nm ...] :in $ %
                              :where [?sa :entity/name "sa"] (traces ?sa ?x) [?x :entity/name ?nm]]
                            db rules))]
      (is (= #{"fa" "m1"} reached)
          "feeds is intra-view flow, NOT a view-map, so sa does not trace to fb"))))

;; ── traces in a LAW: now permitted, because xview is vocab-derived (not law-local) ──
(defstructure ReachAudit
  "Auditor: flags a Step that does NOT trace to any impl Mod. Its law recurses `traces`
   over the vocab-derived `xview`; loading this structure proves the recursion guard no
   longer trips (xview is not one of the law's own :rules)."
  (law "every step traces to an impl module"
    :scope ::Step
    :offenders '[?s]
    :rules '[[(traces ?a ?b) (xview ?a ?b)]
             [(traces ?a ?c) (xview ?a ?b) (traces ?b ?c)]]
    :where '[(not-join [?s] (traces ?s ?m) (Mod ?m))]))

(deftest traces-in-a-law-now-works
  (testing "the traces law is accepted and runs: sb (its concept fb has no realizing Mod) is flagged"
    (let [db (build/vars->cozo [#'m1 #'fb #'fa #'sb #'sa])]
      (is (= #{"sb"}
             (->> (s/check db)
                  (filter #(= "every step traces to an impl module" (:law %)))
                  (mapcat :offenders) (map first)
                  (map #(:entity/name (cq/entity db %))) set))))))

;; ── the functoriality law: a view-map must preserve flow ──────────────────────
(defstructure FlowFunctor
  "Auditor: the :via view-map must preserve flow — if SA :next SB then their mapped
   faculties must feed (FA :feeds FB). A step-sequence whose faculties don't flow is
   cross-view drift. Non-recursive; uses only auto-derived rel-rules (next/via/feeds)."
  (law "view-map preserves flow"
    :scope ::Step
    :offenders '[?a ?b]
    :where '[(next ?a ?b) (via ?a ?fa) (via ?b ?fb) (not (feeds ?fa ?fb))]))

;; drift chain: SC→SD,  SC↦FC SD↦FD,  but FC does NOT feed FD
(Fac fc)
(Fac fd)
(Step sd {:via fd})
(Step sc {:next sd :via fc})

(defn- flow-offenders [db]
  (->> (s/check db)
       (filter #(= "view-map preserves flow" (:law %)))
       (mapcat :offenders)
       (map (fn [row] (set (map #(:entity/name (cq/entity db %)) row))))
       set))

(deftest functoriality-law-catches-cross-view-drift
  (testing "aligned: SA→SB maps to FA feeds FB → no violation"
    (let [db (build/vars->cozo [#'m1 #'fb #'fa #'sb #'sa])]
      (is (empty? (flow-offenders db)))))
  (testing "drift: SC→SD but FC does not feed FD → caught as a violation"
    (let [db (build/vars->cozo [#'fc #'fd #'sd #'sc])]
      (is (= #{#{"sc" "sd"}} (flow-offenders db))))))
