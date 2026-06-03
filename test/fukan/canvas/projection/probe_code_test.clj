(ns fukan.canvas.projection.probe-code-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.probe :refer [Finding]]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probe-code :as pc]))

(deftest finding-carries-shape-and-holds
  (testing "a Finding can declare a contract: a shape and a holds invariant"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Kind "Str")
                 (Finding "F" (gating true)
                   (shape [Str])
                   (holds "the list is empty for a clean model"))))
          fid (ffirst (d/q '[:find ?f :where [?f :entity/name "F"] [?f :structure/of :Finding]] db))]
      (is (= "the list is empty for a clean model"
             (:val/holds (d/entity db fid)))
          "holds is stored as a scalar on the finding")
      (is (seq (d/q '[:find ?sh :in $ ?f
                      :where [?r :rel/from ?f] [?r :rel/kind :shape] [?r :rel/to ?sh]
                             [?sh :structure/of :Shape]] db fid))
          "shape is a Shape value node linked from the finding"))))

(deftest projects-composing-probe-fn-form
  (testing "project-probe reads the model and emits a probe fn that composes its capability"
    (let [db   (cs/build)
          form (:fn-form (pc/project-probe db "integrity"))]
      ;; shape of the emitted form: (fn [target-db] {:lens "integrity" :gating true :finding (mapv str (<var> target-db))})
      (is (= 'fn (first form)) "emits an fn form")
      (is (= '[target-db] (second form)) "takes the target model db")
      (let [body (nth form 2)]
        (is (= "integrity" (:lens body)))
        (is (= true (:gating body)))
        ;; the finding composes the modelled capability `check`, fully qualified
        (is (= '(mapv str (fukan.canvas.core.structure/check target-db))
               (:finding body))
            "the finding wraps the called capability's output as strings")))))

(deftest projects-contract-predicate-from-shape
  (testing "project-probe emits a contract predicate matching the finding's [Str] shape"
    (let [db   (cs/build)
          form (:contract-form (pc/project-probe db "integrity"))
          pred (eval form)]
      ;; the predicate takes a probe RESULT and checks its :finding against the shape
      (is (pred {:lens "integrity" :gating true :finding []})
          "an empty list of strings satisfies [Str]")
      (is (pred {:lens "integrity" :gating true :finding ["a" "b"]})
          "a list of strings satisfies [Str]")
      (is (not (pred {:lens "integrity" :gating true :finding [1 2]}))
          "a list of non-strings violates [Str]")
      (is (not (pred {:lens "integrity" :gating true :finding "nope"}))
          "a non-list violates [Str]"))))
