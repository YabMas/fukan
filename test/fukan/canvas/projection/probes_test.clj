(ns fukan.canvas.projection.probes-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probe-code :as pc]
            [fukan.canvas.projection.probes :as probes]
            [fukan.target.clojure :as target]
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

(deftest probe-integrity-composes-check
  (testing "probe-integrity surfaces the kernel's check as a gating finding"
    (let [db     (cs/build)
          result (probes/probe-integrity db)]
      (is (= "integrity" (:lens result)))
      (is (true? (:gating result)) "integrity is the gating inspect case")
      (is (every? string? (:finding result)) "violations rendered as [Str]")
      (is (empty? (:finding result)) "the self-model's laws all hold — no violations"))))

(deftest probe-patterns-scopes-to-a-focus
  (testing "a probe reads only its focus sub-graph — so a refined focus chains into a probe"
    (let [db        (cs/build)
          whole     (probes/probe-patterns db)
          empty-foc (probes/probe-patterns db #{})            ; no nodes in focus → no relations
          run-foc   (probes/run db "patterns" #{})]
      (is (seq (:finding whole)) "unscoped: patterns across the whole model")
      (is (empty? (:finding empty-foc)) "scoped to an empty focus: no relations, no patterns")
      (is (= empty-foc run-foc) "run passes the focus through to the leaf"))))

(deftest the-full-probe-surface-runs
  (testing "all seven leaves run over the self-model and yield list-of-string findings"
    (let [db  (cs/build)
          all (probes/run-all db)]
      (is (= #{"survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"}
             (set (keys all))) "the full implemented probe surface")
      (is (seq (:finding (all "survey")))  "survey: counts per structure kind")
      (is (seq (:finding (all "tar-pit"))) "tar-pit: connected hotspots")
      (is (false? (:gating (all "survey"))) "survey is a View")
      (is (true?  (:gating (all "drift")))  "drift is a gating Signal")
      (is (every? string? (mapcat :finding (vals all))) "every finding is a list of strings"))))

(deftest coverage-and-drift-surface-correspondence
  (testing "over a unified model (self-model + sample code), the gating probes surface the gaps"
    (let [db    (cs/merge-dbs [(cs/build) (target/extract "test/fixtures/target/sample.clj")])
          drift (probes/run db "drift")
          cov   (probes/run db "coverage")]
      (is (seq (:finding drift)) "modelled Stages drift — sample shares no corresponding module")
      (is (= #{"alpha" "beta" "delta"} (set (:finding cov)))
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
