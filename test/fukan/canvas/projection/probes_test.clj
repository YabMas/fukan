(ns fukan.canvas.projection.probes-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probe-code :as pc]
            [fukan.canvas.projection.probes :as probes]
            [canvas.vocab.shape :refer [Kind]]))

(deftest probe-patterns-honors-its-contract-and-holds
  (testing "the implemented probe-patterns satisfies its projected contract + holds"
    (let [db     (cs/build)
          valid? (eval (:contract-form (pc/project-probe db "patterns")))
          result (probes/probe-patterns db)]
      (is (= "patterns" (:lens result)) "the modelled lens")
      (is (false? (:gating result)) "the modelled gating (a non-gating View)")
      (is (valid? result) "the finding satisfies the projected [Str] shape contract")
      (is (seq (:finding result))
          "the self-model has recurring structures, so patterns are reported")
      (let [tiny (s/with-structures (s/within-module "tiny" (Kind "Solo")))]
        (is (empty? (:finding (probes/probe-patterns tiny)))
            "holds: a degenerate model (one unique structure) → no patterns")))))
