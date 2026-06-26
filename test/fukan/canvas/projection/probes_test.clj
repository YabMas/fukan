(ns fukan.canvas.projection.probes-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.model.pipeline :as pipeline]
            ;; composition root: registers the FACT extractor (for build-model "path") + the Cozo check engine
            [fukan.infra.model]
            [fukan.canvas.projection.probes :as probes]
            [fukan.canvas.core.lens :refer [Projection]]
            [canvas.vocab.code.kind :refer [Kind]]))

;; tiny degenerate models built from top-level defs (assembled per use)
(Kind ^{:name "Solo"} solo)
(Projection ^{:name "Empty"} broken-projection)   ; neither base nor contextualization → a violation

(deftest probe-patterns-yields-observations-with-foci
  (testing "patterns reports recurring structures as observations carrying foci"
    (let [db     (pipeline/build-model nil)
          result (probes/run db "patterns")]
      (is (= "patterns" (:lens result)))
      (is (seq (:observations result)) "the self-model has recurring structures")
      (let [o (first (:observations result))]
        (is (set? (:focus o)) "each observation carries a node-set focus")
        (is (seq (:focus o)) "the focus names the participating nodes")
        (is (= :pattern (:as o)))
        (is (string? (:note o))))
      (let [tiny (build/vars->cozo [#'solo])]
        (is (empty? (:observations (probes/run tiny "patterns")))
            "holds: a degenerate model → no patterns")))))

(deftest probe-integrity-composes-check-into-observations
  (testing "integrity surfaces each violation as an observation whose focus is its offenders"
    (let [db     (pipeline/build-model nil)
          result (probes/run db "integrity")]
      (is (= "integrity" (:lens result)))
      (is (empty? (:observations result)) "the self-model's laws all hold — no violations")
      (let [dirty (build/vars->cozo [#'broken-projection])
            v     (probes/run dirty "integrity")]
        (is (seq (:observations v)) "a broken model reports violations")
        (let [o (first (:observations v))]
          (is (= :violation (:as o)))
          (is (set? (:focus o)) "the focus is the offender node-set")
          (is (seq (:focus o)) "offenders are named")
          (is (string? (:note o))))))))

(deftest probe-patterns-scopes-to-a-focus
  (testing "a probe reads only its focus sub-graph — so a refined focus chains in (via the public run)"
    (let [db        (pipeline/build-model nil)
          whole     (probes/run db "patterns")
          empty-foc (probes/run db "patterns" #{})]
      (is (seq (:observations whole)) "unscoped: patterns across the whole model")
      (is (empty? (:observations empty-foc)) "empty focus: no relations, no patterns — run passes the focus through to the leaf"))))

(deftest the-full-probe-surface-runs
  (testing "all eight leaves run over the self-model and yield observation findings"
    (let [db  (pipeline/build-model nil)
          all (probes/run-all db)]
      (is (= #{"survey" "patterns" "consistency" "callers" "integrity" "coverage" "drift" "type-drift"}
             (set (keys all))) "the full registered probe surface")
      (is (seq (:observations (all "survey")))  "survey: counts per structure kind")
      (is (seq (:observations (all "callers"))) "callers: degree hotspots")
      (is (every? (fn [o] (and (set? (:focus o)) (keyword? (:as o)) (string? (:note o))))
                  (mapcat :observations (vals all)))
          "every observation is {focus tag note}"))))

(deftest coverage-and-drift-surface-correspondence
  (testing "the coverage/drift readings surface gaps as observations over a unified model"
    (let [db    (pipeline/build-model "test/fixtures/target/sample.clj")
          drift (probes/run db "drift")
          cov   (probes/run db "coverage")]
      (is (seq (:observations drift)) "modelled Operations drift")
      (is (every? #(= :gap (:as %)) (:observations drift)) "drift gaps are tagged :gap")
      (is (= #{"alpha" "beta" "delta"} (set (map :note (:observations cov))))
          "sample's Operations are uncovered by the model (the coverage dual)"))))

(deftest run-and-run-all-are-the-live-probe-surface
  (testing "run dispatches a named probe via the multimethod; run-all runs every method"
    (let [db (pipeline/build-model nil)]
      (is (= "integrity" (:lens (probes/run db "integrity")))
          "run dispatches by name to the registered leaf (leaves are internal — exercised through run)")
      (let [all (probes/run-all db)]
        (is (= 8 (count all)) "run-all runs every registered method")
        (is (= "patterns" (:lens (all "patterns"))))
        (is (= "integrity" (:lens (all "integrity")))))
      (is (thrown? clojure.lang.ExceptionInfo (probes/run db "no-such-probe"))
          "an unregistered probe name throws via the :default method")
      (is (contains? (set (keys (methods probes/run-probe))) "survey")
          "leaves self-register as multimethod methods"))))
