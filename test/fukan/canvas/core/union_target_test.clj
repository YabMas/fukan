(ns fukan.canvas.core.union-target-test
  "Union slot targets (`[:* A B]`): registry shape, the generated disjunctive
   target-type law, nested routing into a union slot, and the reflection →
   print-dual round-trip."
  (:require [clojure.test :refer [deftest is]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.projection.grammar :as g]
            ;; side-effect: registers the malli dialect + check engine
            [fukan.infra.model]))

;; ── fixtures ─────────────────────────────────────────────────────────────────

(defstructure UOp "An op-ish member sort.")
(defstructure UKind "A kind-ish member sort.")
(defstructure UEff "A sort OUTSIDE the union — not admissible as a member.")

(defstructure UBox
  "A container whose members are a UNION of sorts."
  {:items [:* UOp UKind]})

(UOp ^{:name "op1"} u-op1)
(UKind ^{:name "k1"} u-k1)
(UEff ^{:name "e1"} u-e1)
(UBox ^{:name "good"} u-good {:items [u-op1 u-k1]})
(UBox ^{:name "bad"} u-bad {:items [u-e1]})

;; nested authoring: a member of either union sort routes into the union slot
(UBox u-nested (UOp inner-op))

;; ── the tests ────────────────────────────────────────────────────────────────

(deftest union-slot-parses-with-alts
  (let [sl (some #(when (= :items (:rel %)) %) (:slots (s/structure-by-tag ::UBox)))]
    (is (= ::UOp (:target sl)) ":target holds the first alternative")
    (is (= [::UOp ::UKind] (:alts sl)) ":alts holds the full ordered union")
    (is (false? (:type-form? sl)) "a union slot is a relation slot, never a type form")))

(deftest union-target-law-checks-the-disjunction
  (let [db        (build/vars->cozo [#'u-op1 #'u-k1 #'u-e1 #'u-good #'u-bad])
        law-name  "UBox.items target must be a UOp|UKind"
        offenders (->> (law/check db)
                       (filter #(= law-name (:law %)))
                       (mapcat :offenders) (map first)
                       (map #(:entity/name (cq/entity db %)))
                       set)]
    (is (= #{"bad"} offenders)
        "either union sort passes; a target outside the union offends")))

(deftest nested-instance-routes-into-the-union-slot
  (let [db (build/vars->cozo [#'u-nested #'inner-op])]
    (is (= #{"inner-op"}
           (set (cq/q '[:find [?n ...] :in $
                        :where [?b :entity/name "u-nested"]
                               [?r :rel/from ?b] [?r :rel/kind :items] [?r :rel/to ?t]
                               [?t :entity/name ?n]]
                      db)))
        "a nested member of a union sort routes into the union slot")))

(deftest union-slot-round-trips-through-the-print-dual
  (let [db (build/with-grammar (build/vars->cozo [#'u-op1]) nil)
        sid (ffirst (cq/q '[:find ?s :in $ ?t
                            :where [?s :structure/of :fukan.canvas.core.reflect/Structure]
                                   [?s :val/tag ?t]]
                          db ":fukan.canvas.core.union-target-test/UBox"))]
    (is (= '(defstructure UBox
              "A container whose members are a UNION of sorts."
              {:items [:* UOp UKind]})
           (g/structure-form db sid))
        "the union reflects as one edge per alternative and renders back as authored")))
