(ns fukan.canvas.projection.probes-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probe-code :as pc]
            [fukan.canvas.projection.probes :as probes]
            [fukan.target.clojure :as target]
            [canvas.vocab.probe :refer [Finding]]
            [canvas.vocab.shape :refer [Kind]]))

(deftest probe-patterns-yields-observations-with-foci
  (testing "patterns reports recurring structures as observations carrying foci"
    (let [db     (cs/build)
          result (probes/probe-patterns db)]
      (is (= "patterns" (:lens result)))
      (is (false? (:gating result)))
      (is (seq (:observations result)) "the self-model has recurring structures")
      (let [o (first (:observations result))]
        (is (set? (:focus o)) "each observation carries a node-set focus")
        (is (seq (:focus o)) "the focus names the participating nodes")
        (is (= :pattern (:as o)))
        (is (string? (:note o))))
      (let [tiny (s/with-structures (s/within-module "tiny" (Kind "Solo")))]
        (is (empty? (:observations (probes/probe-patterns tiny)))
            "holds: a degenerate model → no patterns")))))

(deftest patterns-holds-law-gates-the-finding
  (testing "the projected holds-check passes the real finding and fires on a bogus one"
    (let [db     (cs/build)
          holds? (eval (:holds-check (pc/project-probe db "patterns")))]
      (is (holds? (probes/probe-patterns db) db)
          "the real patterns finding satisfies its holds (the self-model recurs)")
      (let [tiny (s/with-structures (s/within-module "tiny" (Kind "Solo")))]
        (is (holds? {:lens "patterns" :gating false :finding []} tiny)
            "empty finding over a degenerate model → holds")
        (is (not (holds? {:lens "patterns" :gating false :finding ["bogus pattern"]} tiny))
            "a reported pattern over a model with no recurrence → holds FIRES")))))

(deftest integrity-holds-law-gates-the-finding
  (testing "the projected holds-check for integrity passes real and fires on a bogus finding"
    (let [db     (cs/build)
          holds? (eval (:holds-check (pc/project-probe db "integrity")))]
      (is (holds? {:lens "integrity" :gating true :finding []} db)
          "empty finding over the clean self-model → holds")
      (is (not (holds? {:lens "integrity" :gating true :finding ["bogus violation"]} db))
          "a reported violation when the model is clean → holds FIRES"))))

(deftest probe-integrity-composes-check-into-observations
  (testing "integrity surfaces each violation as an observation whose focus is its offenders"
    (let [db     (cs/build)
          result (probes/probe-integrity db)]
      (is (= "integrity" (:lens result)))
      (is (true? (:gating result)) "integrity is the gating inspect case")
      (is (empty? (:observations result)) "the self-model's laws all hold — no violations")
      (let [dirty (s/with-structures
                    (s/within-module "broken" (Kind "Str")
                      (Finding "Orphan" (gating false))))
            v     (probes/probe-integrity dirty)]
        (is (seq (:observations v)) "a broken model reports violations")
        (let [o (first (:observations v))]
          (is (= :violation (:as o)))
          (is (set? (:focus o)) "the focus is the offender node-set")
          (is (seq (:focus o)) "offenders are named")
          (is (string? (:note o))))))))

(deftest probe-patterns-scopes-to-a-focus
  (testing "a probe reads only its focus sub-graph — so a refined focus chains in"
    (let [db        (cs/build)
          whole     (probes/probe-patterns db)
          empty-foc (probes/probe-patterns db #{})
          run-foc   (probes/run db "patterns" #{})]
      (is (seq (:observations whole)) "unscoped: patterns across the whole model")
      (is (empty? (:observations empty-foc)) "empty focus: no relations, no patterns")
      (is (= empty-foc run-foc) "run passes the focus through to the leaf"))))

(deftest the-full-probe-surface-runs
  (testing "all seven leaves run over the self-model and yield observation findings"
    (let [db  (cs/build)
          all (probes/run-all db)]
      (is (= #{"survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"}
             (set (keys all))) "the full registered probe surface")
      (is (seq (:observations (all "survey")))  "survey: counts per structure kind")
      (is (seq (:observations (all "tar-pit"))) "tar-pit: connected hotspots")
      (is (false? (:gating (all "survey"))) "survey is a View")
      (is (true?  (:gating (all "drift")))  "drift is a gating Signal")
      (is (every? (fn [o] (and (set? (:focus o)) (keyword? (:as o)) (string? (:note o))))
                  (mapcat :observations (vals all)))
          "every observation is {focus tag note}"))))

(deftest coverage-and-drift-surface-correspondence
  (testing "the gating probes surface gaps as observations over a unified model"
    (let [db    (cs/merge-dbs [(cs/build) (target/extract "test/fixtures/target/sample.clj")])
          drift (probes/run db "drift")
          cov   (probes/run db "coverage")]
      (is (seq (:observations drift)) "modelled Stages drift")
      (is (every? #(= :gap (:as %)) (:observations drift)) "drift gaps are tagged :gap")
      (is (= #{"alpha" "beta" "delta"} (set (map :note (:observations cov))))
          "sample's Operations are uncovered by the model (the coverage dual)"))))

(deftest run-and-run-all-are-the-live-probe-surface
  (testing "run dispatches a named probe via the multimethod; run-all runs every method"
    (let [db (cs/build)]
      (is (= (probes/probe-integrity db) (probes/run db "integrity"))
          "run dispatches by name to the registered leaf")
      (let [all (probes/run-all db)]
        (is (= 7 (count all)) "run-all runs every registered method")
        (is (= "patterns" (:lens (all "patterns"))))
        (is (= "integrity" (:lens (all "integrity")))))
      (is (thrown? clojure.lang.ExceptionInfo (probes/run db "no-such-probe"))
          "an unregistered probe name throws via the :default method")
      (is (contains? (set (keys (methods probes/run-probe))) "survey")
          "leaves self-register as multimethod methods"))))
