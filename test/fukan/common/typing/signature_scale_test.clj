(ns fukan.common.typing.signature-scale-test
  "What `:correspondence/signature-disagrees` COSTS as a model's signatures grow.

   The law compares the signatures of paired Operations and functions, and a consumer adds
   signatures by the thousand. Until 2026-11-27 its multi-arity case asked, under a negation,
   whether an arity had an agreeing arity on the other side through `arrow-agrees` — and Cozo
   evaluates what a negation reaches over its whole domain, so the law paid for every pair of
   distinct arrow schemas in the model: 114 s and ~2 GB of native memory on nido's 2,203, for
   a law whose answer was empty. What it may cost is what its pairs cost, plus the one product
   left standing by design — the arities of multi-arity function schemas against each other
   (`arity-differs`' docstring states it).

   This model holds that line: a thousand distinct arrow signatures, three of them paired with
   an Operation, a handful of multi-arity schemas. Written the old way the whole check here took
   13.8 s, against 0.95 s now; the budget below gives each law 5, which the old law outran."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.substrate :as sub]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.typing.malli :as malli]
            [fukan.common.vocab.code.module :as module]
            [fukan.common.vocab.code.operation :as operation]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(def ^:private n-functions 1000)

(defn- arrow
  "The `i`-th distinct single-arity signature: an integer parameter whose lower bound is `i`, and a
   string. Two parameters, because the old comparison's cost was per pair of arrows PER PARAMETER."
  [i]
  [:=> [:cat [:int {:min i}] :string] :string])

(def ^:private multi-arity
  "A few multi-arity schemas — enough that the arity comparison has work, few enough that its
   stated quadratic stays small."
  (vec (for [i (range 5)]
         [:function (arrow (+ n-functions i)) [:=> [:cat :string :int] [:int {:min i}]]])))

(defn- sig-iv [form] (s/value-literal->iv ::malli/Schema form))

(defn- node [tag nm slots]
  (sub/->InstanceValue tag nm nil {}
                       (vec (for [[rk card targets] slots] {:rk rk :card card :targets targets}))
                       false))

(defn- fn-root [nm form]
  [(str "fn/" nm) (node ::clj-op/Fn nm [[:signature :optional [(sig-iv form)]]])])

(defn- op-root [nm form]
  [(str "op/" nm) (node ::operation/Operation nm [[:signature :optional [(sig-iv form)]]])])

(def ^:private paired
  "The design's three claims: one agreeing arrow, one disagreeing arrow, and one multi-arity
   function written with its arities in the other order (which agrees)."
  [["f0" (arrow 0)]
   ["f1" (arrow 7)]
   ["m0" (let [[h a b] (multi-arity 0)] [h b a])]])

(defn- model []
  (let [fns  (concat (for [i (range n-functions)] (fn-root (str "f" i) (arrow i)))
                     (for [[i form] (map-indexed vector multi-arity)] (fn-root (str "m" i) form)))
        ops  (for [[nm form] paired] (op-root nm form))
        ns   ["ns/app.scale.thing"
              (node ::clj-module/Ns "app.scale.thing"
                    [[:child :many (mapv (comp sub/->Ref first) fns)]])]
        mod  ["mod/scale-thing"
              (node ::module/Module "scale-thing"
                    [[:child :many (mapv (comp sub/->Ref first) ops)]])]]
    (build/instances->cozo (vec (concat fns ops [ns mod])))))

(deftest a-thousand-signatures-cost-what-their-pairs-cost
  (let [db (model)]
    (testing "the model is the one described: a thousand distinct arrows, plus the arities"
      (is (<= n-functions
              (count (cq/q '[:find ?s :in $ % :where (arrow ?s)] db (s/vocab-rules))))))
    (testing "the whole check decides, every law inside a budget far below the shipped 120 s —
              `check` throws, naming the law, on one that outruns it"
      (let [t0   (System/nanoTime)
            rows (binding [law/*law-budget-ms* 5000]
                   (law/violation-rows db :correspondence/signature-disagrees))
            secs (/ (- (System/nanoTime) t0) 1e9)]
        (is (= #{["f1" "scale-thing"]} rows)
            "and its answer is still the comparison: the one pair that disagrees, and not the
             multi-arity pair whose arities are written the other way round")
        (is (< secs 5.0) (str "the check took " secs " s"))))))
