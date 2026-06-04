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

(deftest run-and-run-all-are-the-live-probe-surface
  (testing "run dispatches a named probe; run-all runs every implemented leaf"
    (let [db (cs/build)]
      (is (= (probes/probe-integrity db) (probes/run db "integrity"))
          "run dispatches by name to the implemented leaf")
      (let [all (probes/run-all db)]
        (is (= #{"patterns" "integrity"} (set (keys all))) "every implemented probe ran")
        (is (= "patterns" (:lens (all "patterns"))))
        (is (= "integrity" (:lens (all "integrity")))))
      (is (thrown? clojure.lang.ExceptionInfo (probes/run db "survey"))
          "an unimplemented (modelled-only) probe throws"))))
