(ns fukan.agent.close-drift-test
  "Phase 8 Sprint 3 — close-drift-plan / close-drift-verify / close-drift
   unit tests. Cover scope filters, truncation, unhandled extraction,
   classification across :closed / :failed / :no-report, and convenience
   wrapper composition with a stubbed dispatch-fn.

   Like the api-test fixtures touching canvas-db, these tests run against
   the live canvas db (`canvas-source/build-canvas-db`) — there is no
   small_model substitute that carries canvas declarations. Ground-truth
   stable-ids come from `canvas/distributed/cluster.clj` and
   `canvas/distributed/log.clj` (Phase 7 trial-run material).

   The fukan model is loaded once via the same harness path as `clj
   -M:run` so `(canvas-drift)` produces real findings."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [fukan.agent.api :as api]
            [fukan.infra.model :as infra-model]
            [fukan.model.pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; Fixture — load the live fukan model once. Drift findings against
;; `canvas/distributed/*` are the test surface.

(defonce ^:private model-once
  (delay
    (let [m (pipeline/build-model "/Users/yabmas/Code/fukan/src")]
      (infra-model/set-model-for-test! m)
      m)))

(defn with-live-model [f]
  @model-once
  (f))

(use-fixtures :once with-live-model)

;; ---------------------------------------------------------------------------
;; close-drift-plan

(deftest plan-with-module-coord-scope
  (testing "(close-drift-plan :module-coord …) filters findings to that subtree"
    (let [p (api/close-drift-plan :module-coord "distributed.cluster" :limit 25)]
      (is (map? p))
      (is (contains? p :plan))
      (is (contains? p :batches))
      (is (contains? p :counts))
      (is (= "distributed.cluster" (-> p :scope :module-coord)))
      (is (every? (fn [e] (str/starts-with? (:stable-id e) "distributed.cluster"))
                  (:plan p))
          "every plan entry's stable-id sits under the scope")
      (is (every? string? (map :rendered (:plan p))))
      (is (every? #(contains? % :batch-key) (:plan p))))))

(deftest plan-renders-instructions-per-finding
  (testing "Plan entries carry rendered markdown instructions"
    (let [p (api/close-drift-plan :module-coord "distributed.cluster" :limit 5)]
      (when (seq (:plan p))
        (let [e (first (:plan p))]
          (is (string? (:rendered e)))
          (is (str/includes? (:rendered e) "Implementation instruction"))
          (is (= :code-side/drift-close (:scenario e))))))))

(deftest plan-groups-by-batch-key
  (testing ":batches groups plan entries by :expected-code-path"
    (let [p (api/close-drift-plan :module-coord "distributed" :limit 25)]
      (is (map? (:batches p)))
      (doseq [[k entries] (:batches p)]
        (is (every? (fn [e] (= k (:batch-key e))) entries))))))

(deftest plan-default-limit-and-truncation
  (testing ":limit truncates and sets :truncated? / :remaining"
    (let [p (api/close-drift-plan :module-coord "distributed" :limit 2)]
      (is (<= (count (:plan p)) 2))
      (when (> (-> p :counts :findings-total) 2)
        (is (true? (:truncated? p)))
        (is (pos? (:remaining p)))))))

(deftest plan-stable-id-scope-overrides-broader-filters
  (testing ":stable-id reduces to a single finding"
    (let [;; Use a known drift target. Skip if absent (clean canvas).
          all (api/close-drift-plan :module-coord "distributed.cluster" :limit 25)
          sid (some-> all :plan first :stable-id)]
      (when sid
        (let [single (api/close-drift-plan :stable-id sid)]
          (is (= 1 (count (:plan single))))
          (is (= sid (-> single :plan first :stable-id))))))))

(deftest plan-empty-scope-returns-empty-plan
  (testing "scope that matches no canvas module returns empty plan"
    (let [p (api/close-drift-plan :module-coord "no.such.module.anywhere"
                                  :limit 25)]
      (is (= [] (:plan p)))
      (is (= 0 (-> p :counts :findings-total))))))

(deftest plan-rejects-unknown-opts
  (testing "unknown opt keys throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unknown close-drift-plan filter"
                          (api/close-drift-plan :wat 42)))))

(deftest plan-unhandled-extracts-unsupported-drift-kinds
  (testing ":unhandled vector captures findings with no scenario"
    ;; Synthetic test via with-redefs: pretend canvas-drift returns a
    ;; finding with a never-registered :check kind.
    (let [synthetic [{:check    :inspect.drift/never-heard-of
                      :severity :warning
                      :message  "synthetic"
                      :offenders [{:stable-id          "synthetic.module/Whatever"
                                   :expected-code-path "src/synthetic/module.clj"
                                   :expected-symbol    "Whatever"
                                   :canvas-kind        :function}]
                      :detail   {}}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p (api/close-drift-plan)]
          (is (= 1 (count (:unhandled p))))
          (is (= :scenario-not-found
                 (-> p :unhandled first :reason)))
          (is (= 0 (count (:plan p)))))))))

(deftest plan-echoes-max-attempts-default
  (testing ":max-attempts defaults to 2 and is echoed in the return"
    (let [p (api/close-drift-plan :module-coord "no.such.module" :limit 25)]
      (is (= 2 (:max-attempts p))))))

(deftest plan-honors-max-attempts-opt
  (testing ":max-attempts is plumbed through verbatim"
    (let [p (api/close-drift-plan :module-coord "no.such.module"
                                  :max-attempts 3)]
      (is (= 3 (:max-attempts p))))))

;; ---------------------------------------------------------------------------
;; close-drift-verify

(deftest verify-closed-case
  (testing "Finding absent from post-drift → :closed"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:expected-code-path "p"}
                        :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "wrote it" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (= 1 (count (:per-finding v))))
          (is (= :closed (-> v :per-finding first :outcome)))
          (is (= 1 (-> v :counts :findings-closed)))
          (is (= 0 (-> v :counts :findings-failed)))
          (is (string? (:rendered v))))))))

(deftest verify-failed-with-retry-available
  (testing "Finding still present + attempts < max → :failed + :requires-retry?"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:expected-code-path "p"}
                        :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "tried" :attempt 1}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :message "still gone"
                        :offenders [{:stable-id "x/foo"
                                     :expected-code-path "p"
                                     :expected-symbol "foo"}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :failed (:outcome pf)))
          (is (true? (:requires-retry? pf)))
          (is (nil? (:escalation-reason pf)))
          (is (= 1 (-> v :counts :findings-failed))))))))

(deftest verify-failed-attempts-exhausted
  (testing "Finding still present + attempts >= max → :failed + :attempts-exhausted"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 1}
          reports [{:stable-id "x/foo" :report "first" :attempt 1}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :message "still gone"
                        :offenders [{:stable-id "x/foo"
                                     :expected-code-path "p"
                                     :expected-symbol "foo"}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :failed (:outcome pf)))
          (is (false? (:requires-retry? pf)))
          (is (= :attempts-exhausted (:escalation-reason pf)))
          (is (pos? (-> v :counts :findings-escalated))))))))

(deftest verify-no-report-case
  (testing "Plan entry without a matching report → :no-report"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports []]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :no-report (:outcome pf)))
          (is (= :no-report (:escalation-reason pf)))
          (is (false? (:requires-retry? pf))))))))

(deftest verify-unhandled-entries-counted-as-escalated
  (testing "Plan :unhandled entries flow through to verify report"
    (let [plan {:plan []
                :unhandled [{:stable-id "x/foo"
                             :check :inspect.drift/never-heard-of
                             :reason :scenario-not-found
                             :detail "synthetic"}]
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports []]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (= 1 (count (:per-finding v))))
          (is (= :scenario-not-found
                 (-> v :per-finding first :escalation-reason)))
          (is (= :failed (-> v :per-finding first :outcome))))))))

(deftest verify-rendered-markdown-includes-summary
  (testing ":rendered carries scope + summary + per-finding outcomes"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "done" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (str/includes? (:rendered v) "Close-drift report"))
          (is (str/includes? (:rendered v) "module-coord"))
          (is (str/includes? (:rendered v) "closed")))))))

(deftest verify-rejects-malformed-plan
  (testing "Plan that isn't from close-drift-plan throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"plan must be the return"
                          (api/close-drift-verify :plan "not a plan"
                                                  :reports [])))))

;; ---------------------------------------------------------------------------
;; close-drift convenience wrapper

(deftest close-drift-composes-plan-and-verify-with-stub
  (testing "close-drift end-to-end with injected stub dispatch-fn"
    (let [;; Use a real canvas stable-id so (spec …) resolves; the test
          ;; pretends drift is present then absent.
          synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :message "synthetic"
                      :offenders [{:stable-id "distributed.cluster/get_self_role"
                                   :expected-code-path "src/fukan/distributed/cluster.clj"
                                   :expected-symbol "get-self-role"
                                   :canvas-kind :function}]}]
          dispatched (atom [])
          stub-fn (fn [{:keys [stable-id rendered]}]
                    (swap! dispatched conj {:stable-id stable-id
                                            :rendered-preview (subs rendered 0 (min 20 (count rendered)))})
                    {:stable-id stable-id
                     :report "stub closed it"
                     :attempt 1})]
      ;; First call: simulate drift present. Verify re-call: simulate drift gone.
      (let [call-count (atom 0)
            drift-fn (fn [& _]
                       (swap! call-count inc)
                       (if (= 1 @call-count) synthetic []))]
        (with-redefs [api/canvas-drift drift-fn]
          (let [v (api/close-drift :dispatch-fn stub-fn)]
            (is (= 1 (count @dispatched)) "dispatch-fn called once per plan entry")
            (is (= 1 (count (:per-finding v))))
            (is (= :closed (-> v :per-finding first :outcome)))))))))

(deftest close-drift-stub-default-returns-manual-dispatch-required
  (testing "Default dispatch-fn surfaces the architect-required message"
    (let [;; Use a real canvas stable-id so (spec …) resolves; the test
          ;; pretends drift is present then absent.
          synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :message "synthetic"
                      :offenders [{:stable-id "distributed.cluster/get_self_role"
                                   :expected-code-path "src/fukan/distributed/cluster.clj"
                                   :expected-symbol "get-self-role"
                                   :canvas-kind :function}]}]
          call-count (atom 0)
          drift-fn (fn [& _]
                     (swap! call-count inc)
                     ;; Stub default dispatch doesn't actually close — both pre and post drift surface the finding.
                     synthetic)]
      (with-redefs [api/canvas-drift drift-fn]
        (let [v (api/close-drift)]
          (is (str/includes? (-> v :per-finding first :report-excerpt)
                             "manual dispatch required")))))))

(deftest close-drift-stub-injection-shape
  (testing "Stub dispatch-fn receives :stable-id, :rendered, :context"
    (let [;; Use a real canvas stable-id so (spec …) resolves; the test
          ;; pretends drift is present then absent.
          synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :message "synthetic"
                      :offenders [{:stable-id "distributed.cluster/get_self_role"
                                   :expected-code-path "src/fukan/distributed/cluster.clj"
                                   :expected-symbol "get-self-role"
                                   :canvas-kind :function}]}]
          captured (atom nil)
          stub-fn (fn [payload]
                    (reset! captured payload)
                    {:stable-id (:stable-id payload)
                     :report "ok"
                     :attempt 1})]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (api/close-drift :dispatch-fn stub-fn)
        (is (contains? @captured :stable-id))
        (is (contains? @captured :rendered))
        (is (contains? @captured :context))
        (is (= "distributed.cluster/get_self_role" (:stable-id @captured)))
        (is (= "src/fukan/distributed/cluster.clj"
               (-> @captured :context :expected-code-path)))))))

;; ---------------------------------------------------------------------------
;; Smoke test against canvas/distributed/* — Task 9 (stubbed-dispatch).
;; Validates that plan+verify shape works end-to-end against real
;; (canvas-drift) output without needing the Agent tool.

(deftest smoke-test-distributed-stub-dispatch
  (testing "End-to-end stubbed close-drift against canvas/distributed/*"
    (let [;; Use a stub that does nothing — every finding stays as :failed.
          noop-stub (fn [{:keys [stable-id]}]
                      {:stable-id stable-id
                       :report "no-op stub — nothing edited"
                       :attempt 1})
          t0 (System/currentTimeMillis)
          p (api/close-drift-plan :module-coord "distributed" :limit 50)
          t1 (System/currentTimeMillis)
          v (api/close-drift :module-coord "distributed"
                             :limit 50
                             :dispatch-fn noop-stub)
          t2 (System/currentTimeMillis)
          plan-ms (- t1 t0)
          full-ms (- t2 t1)]
      ;; Capture timing as a structural assertion (within sane bounds) and
      ;; print for the sprint report's latency observation.
      (println (str "[smoke] close-drift-plan: " plan-ms "ms; "
                    "close-drift (plan+verify+noop dispatch): " full-ms "ms; "
                    "findings-planned=" (-> p :counts :findings-planned) "; "
                    "outcomes=" (frequencies (map :outcome (:per-finding v)))))
      (is (map? p))
      (is (map? v))
      (is (vector? (:plan p)))
      (is (vector? (:per-finding v)))
      ;; The noop stub never closes anything; if there were findings to plan,
      ;; their outcomes should be :failed (with retry available or exhausted).
      (when (seq (:plan p))
        (is (every? (fn [pf] (#{:failed :no-report} (:outcome pf)))
                    (:per-finding v))
            "noop dispatch ⇒ no finding closes"))
      (is (< plan-ms 30000) "close-drift-plan completes in under 30s")
      (is (< full-ms 30000) "close-drift completes in under 30s"))))
