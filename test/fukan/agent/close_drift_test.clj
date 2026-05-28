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
          (is (= :attempts-exhausted (-> pf :escalation-reason :trigger))
              "Sprint 4 — escalation-reason is now a structured map")
          (is (string? (-> pf :escalation-reason :detail)))
          (is (pos? (-> v :counts :findings-escalated))))))))

(deftest verify-no-report-case
  (testing "Plan entry without a matching report → :no-report + :dispatch-error trigger"
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
          (is (= :dispatch-error (-> pf :escalation-reason :trigger))
              "no-report is classified as a :dispatch-error per Sprint 4 Task 12")
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
                 (-> v :per-finding first :escalation-reason :trigger)))
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
                     :attempt 1})
          ;; First call: simulate drift present. Verify re-call: simulate drift gone.
          call-count (atom 0)
          drift-fn (fn [& _]
                     (swap! call-count inc)
                     (if (= 1 @call-count) synthetic []))]
      (with-redefs [api/canvas-drift drift-fn]
        (let [v (api/close-drift :dispatch-fn stub-fn)]
          (is (= 1 (count @dispatched)) "dispatch-fn called once per plan entry")
          (is (= 1 (count (:per-finding v))))
          (is (= :closed (-> v :per-finding first :outcome))))))))

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

;; ---------------------------------------------------------------------------
;; Sprint 4 — Task 10: Iter-2 instruction rendering

(deftest plan-retry-of-renders-iter-2-instruction
  (testing "(close-drift-plan :retry-of …) wraps iter-1 instruction with reconciliation prose"
    (let [;; Synthetic drift finding present in canvas-drift output.
          synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :message "synthetic"
                      :offenders [{:stable-id          "distributed.cluster/get_self_role"
                                   :expected-code-path "src/fukan/distributed/cluster.clj"
                                   :expected-symbol    "get-self-role"
                                   :canvas-kind        :function}]}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p (api/close-drift-plan
                  :retry-of "distributed.cluster/get_self_role"
                  :iter-1-report "I tried to write the fn but the path was wrong."
                  :iter-1-drift {:check :inspect.drift/missing-implementation
                                 :message "still missing"
                                 :offender {:stable-id "distributed.cluster/get_self_role"
                                            :expected-code-path "src/fukan/distributed/cluster.clj"
                                            :expected-symbol "get-self-role"}})]
          (is (= 1 (count (:plan p))))
          (let [entry (first (:plan p))
                rendered (:rendered entry)]
            (is (str/includes? rendered "iteration 2 of a drift-closure attempt"))
            (is (str/includes? rendered "## Iter-1 subagent report"))
            (is (str/includes? rendered "I tried to write the fn but the path was wrong."))
            (is (str/includes? rendered "## Iter-1 drift state"))
            (is (str/includes? rendered "## Original instruction"))
            (is (str/includes? rendered "Implementation instruction")
                "wrapped instruction still carries the original Layer A+B body")
            (is (= 2 (-> entry :context :attempt))
                ":context :attempt flagged as 2 for the architect's loop")
            (is (= "distributed.cluster/get_self_role"
                   (-> entry :context :retry-of)))))))))

(deftest plan-retry-of-scope-is-implicit-single-finding
  (testing ":retry-of with scope opts throws — scope is implicit"
    (let [synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :offenders [{:stable-id "x/foo"
                                   :expected-code-path "p"
                                   :expected-symbol "foo"
                                   :canvas-kind :function}]}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"exclusive with"
                              (api/close-drift-plan
                                :retry-of "x/foo"
                                :module-coord "x")))))))

(deftest plan-retry-of-missing-finding-throws
  (testing ":retry-of throws when the finding is no longer in current drift"
    (with-redefs [api/canvas-drift (fn [& _] [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not present in current drift"
                            (api/close-drift-plan
                              :retry-of "no.such/finding"
                              :iter-1-report "tried"
                              :iter-1-drift nil))))))

(deftest plan-iter-1-attempt-marker-set-on-context
  (testing "iter-1 plan-entries carry :attempt 1 on :context"
    (let [synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :offenders [{:stable-id "distributed.cluster/get_self_role"
                                   :expected-code-path "src/fukan/distributed/cluster.clj"
                                   :expected-symbol "get-self-role"
                                   :canvas-kind :function}]}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p (api/close-drift-plan :module-coord "distributed.cluster")]
          (is (every? #(= 1 (-> % :context :attempt)) (:plan p))))))))

;; ---------------------------------------------------------------------------
;; Sprint 4 — Task 12: Escalation classification (six triggers)

(deftest escalation-trigger-attempts-exhausted
  (testing ":attempts-exhausted fires when attempts >= max-attempts + still present"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 1}
          reports [{:stable-id "x/foo" :report "tried" :attempt 1}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :offenders [{:stable-id "x/foo"
                                     :expected-code-path "p"
                                     :expected-symbol "foo"}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :attempts-exhausted (-> pf :escalation-reason :trigger)))
          (is (str/includes? (-> pf :escalation-reason :detail) "attempts")))))))

(deftest escalation-trigger-scenario-not-found
  (testing ":scenario-not-found surfaces from plan :unhandled with structured reason"
    (let [synthetic [{:check    :inspect.drift/never-heard-of
                      :severity :warning
                      :offenders [{:stable-id "synthetic/Whatever"
                                   :expected-code-path "p"
                                   :expected-symbol "Whatever"
                                   :canvas-kind :function}]}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p (api/close-drift-plan)
              v (api/close-drift-verify :plan p :reports [])
              pf (-> v :per-finding first)]
          (is (= :scenario-not-found (-> pf :escalation-reason :trigger)))
          (is (string? (-> pf :escalation-reason :detail))))))))

(deftest escalation-trigger-no-projection-registered
  (testing ":no-projection-registered surfaces when (spec …) rejects the canvas-kind"
    (let [synthetic [{:check :inspect.drift/missing-implementation
                      :severity :warning
                      :offenders [{:stable-id          "x/Whatever"
                                   :expected-code-path "src/x.clj"
                                   :expected-symbol    "whatever"
                                   :canvas-kind        :function}]}]]
      ;; Stub instruct to throw the substrate's "no project-lens projection
      ;; registered" exception — same shape Layer A's default method
      ;; raises.
      (with-redefs [api/canvas-drift (fn [& _] synthetic)
                    api/instruct (fn [_ _ & _]
                                   (throw (ex-info
                                            "no project-lens projection registered for kind ?"
                                            {:type :no-projection})))]
        (let [p (api/close-drift-plan)
              v (api/close-drift-verify :plan p :reports [])
              pf (-> v :per-finding first)]
          (is (= 0 (count (:plan p))))
          (is (= 1 (count (:unhandled p))))
          (is (= :no-projection-registered
                 (-> p :unhandled first :reason)))
          (is (= :no-projection-registered
                 (-> pf :escalation-reason :trigger))))))))

(deftest escalation-trigger-dispatch-error
  (testing ":dispatch-error fires when the report carries :error true"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report nil
                    :error "Agent timeout" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :failed (:outcome pf)))
          (is (= :dispatch-error (-> pf :escalation-reason :trigger)))
          (is (str/includes? (-> pf :escalation-reason :detail) "Agent")))))))

(deftest escalation-trigger-dispatch-error-from-no-report
  (testing ":dispatch-error also fires when no report exists for a plan entry"
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
          (is (= :dispatch-error (-> pf :escalation-reason :trigger))))))))

(deftest escalation-structured-reason-shape
  (testing "Every structured escalation-reason carries :trigger + :detail"
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
                        :offenders [{:stable-id "x/foo"
                                     :expected-code-path "p"
                                     :expected-symbol "foo"}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              er (-> v :per-finding first :escalation-reason)]
          (is (map? er))
          (is (keyword? (:trigger er)))
          (is (string? (:detail er))))))))

;; ---------------------------------------------------------------------------
;; Sprint 4 — Task 13: Canvas-side hint heuristics

(deftest canvas-side-hint-stubbed-invariant-fires
  (testing "Heuristic (a) — invariant stubbed across both attempts → hint"
    (let [plan {:plan [{:stable-id "x/SomeInvariant"
                        :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:expected-code-path "src/x.clj"
                                  :canvas-kind :invariant
                                  :attempt 1}
                        :batch-key "src/x.clj"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/SomeInvariant"
                    :report "I could not implement — not enough context to determine the predicate body."
                    :attempt 1}
                   {:stable-id "x/SomeInvariant"
                    :report "TODO: not yet implemented; placeholder body."
                    :attempt 2}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :offenders [{:stable-id "x/SomeInvariant"
                                     :expected-code-path "src/x.clj"
                                     :expected-symbol "some-invariant?"
                                     :canvas-kind :invariant}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (some? (:canvas-side-hint pf)))
          (is (= :predicate-stubbed-twice (-> pf :canvas-side-hint :reason)))
          (is (= :canvas-side-hint (-> pf :escalation-reason :trigger)))
          (is (= :predicate-stubbed-twice (-> pf :escalation-reason :hint-kind))))))))

(deftest canvas-side-hint-stubbed-invariant-skipped-on-single-attempt
  (testing "Heuristic (a) requires >= 2 attempts before firing"
    (let [plan {:plan [{:stable-id "x/SomeInvariant"
                        :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:expected-code-path "src/x.clj"
                                  :canvas-kind :invariant
                                  :attempt 1}
                        :batch-key "src/x.clj"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/SomeInvariant"
                    :report "not yet implemented"
                    :attempt 1}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :offenders [{:stable-id "x/SomeInvariant"
                                     :expected-code-path "src/x.clj"
                                     :expected-symbol "some-invariant?"
                                     :canvas-kind :invariant}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (nil? (:canvas-side-hint pf))
              "single attempt should not fire heuristic (a)"))))))

(deftest canvas-side-hint-shape-drift-fires
  (testing "Heuristic (b) — recent canvas + stable src → hint"
    ;; The private heuristic uses file-mtime; rather than stub a private
    ;; var (problematic), exercise the inner fn via its var directly
    ;; with a redefed file-mtime that returns synthetic mtimes
    ;; matching the contract: recent canvas (now), stable src (10d ago).
    (let [now            (System/currentTimeMillis)
          ten-days-ago   (- now (* 10 24 60 60 1000))
          heuristic-var  #'fukan.agent.api/canvas-side-hint-shape-drift]
      (with-redefs [fukan.agent.api/file-mtime
                    (fn [path]
                      (cond
                        (str/starts-with? (str path) "canvas/") now
                        (str/starts-with? (str path) "src/")    ten-days-ago
                        :else                                    nil))
                    fukan.agent.api/canvas-source-path-for
                    (fn [_] "canvas/x/thing.clj")]
        (let [pe {:stable-id "x.thing/type/SomeRecord"
                  :check     :inspect.drift/shape-drift-on-record
                  :context   {:expected-code-path "src/fukan/x/thing.clj"
                              :canvas-kind        :type
                              :attempt            1}}
              post {:check :inspect.drift/shape-drift-on-record
                    :offenders [{:stable-id "x.thing/type/SomeRecord"}]}
              hint (heuristic-var pe post)]
          (is (some? hint))
          (is (= :recent-canvas-stable-src (:reason hint)))
          (is (str/includes? (:detail hint) "canvas")))))))

(deftest canvas-side-hint-shape-drift-skipped-when-src-also-recent
  (testing "Heuristic (b) — src recently touched too → no hint"
    (let [now            (System/currentTimeMillis)
          heuristic-var  #'fukan.agent.api/canvas-side-hint-shape-drift]
      (with-redefs [fukan.agent.api/file-mtime (fn [_] now)
                    fukan.agent.api/canvas-source-path-for
                    (fn [_] "canvas/x/thing.clj")]
        (let [pe {:stable-id "x.thing/type/SomeRecord"
                  :check     :inspect.drift/shape-drift-on-record
                  :context   {:expected-code-path "src/fukan/x/thing.clj"
                              :canvas-kind        :type
                              :attempt            1}}
              post {:check :inspect.drift/shape-drift-on-record
                    :offenders [{:stable-id "x.thing/type/SomeRecord"}]}]
          (is (nil? (heuristic-var pe post))))))))

(deftest canvas-side-hint-stubbed-invariant-needs-canvas-kind
  (testing "Heuristic (a) is keyed on canvas-kind :invariant, not function"
    (let [plan {:plan [{:stable-id "x/some_function"
                        :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:expected-code-path "src/x.clj"
                                  :canvas-kind :function
                                  :attempt 1}
                        :batch-key "src/x.clj"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/some_function"
                    :report "not yet implemented" :attempt 1}
                   {:stable-id "x/some_function"
                    :report "TODO placeholder" :attempt 2}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :offenders [{:stable-id "x/some_function"
                                     :expected-code-path "src/x.clj"
                                     :expected-symbol "some-function"
                                     :canvas-kind :function}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (nil? (:canvas-side-hint pf))
              "function-kind doesn't trigger the stubbed-invariant hint"))))))

;; ---------------------------------------------------------------------------
;; Sprint 4 — Task 14: Per-attempt timing + aggregate observability

(deftest verify-per-attempt-shape
  (testing ":per-attempt vector carries one entry per report with timing"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "first attempt"
                    :attempt 1 :elapsed-ms 1500}
                   {:stable-id "x/foo" :report "second attempt"
                    :attempt 2 :elapsed-ms 2300}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= 2 (count (:per-attempt pf))))
          (is (= 1 (-> pf :per-attempt first :attempt)))
          (is (= 1500 (-> pf :per-attempt first :elapsed-ms)))
          (is (= 2 (-> pf :per-attempt second :attempt)))
          (is (= 2300 (-> pf :per-attempt second :elapsed-ms)))
          (is (str/includes? (-> pf :per-attempt first :report-excerpt) "first")))))))

(deftest verify-aggregate-timing
  (testing ":counts surfaces total-attempts, closure rates, total-elapsed-ms"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}
                       {:stable-id "x/bar" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "ok" :attempt 1 :elapsed-ms 1000}
                   {:stable-id "x/bar" :report "first" :attempt 1 :elapsed-ms 500}
                   {:stable-id "x/bar" :report "second" :attempt 2 :elapsed-ms 700}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v  (api/close-drift-verify :plan plan :reports reports)
              c  (:counts v)]
          (is (= 3 (:total-attempts c)))
          (is (= 2200 (:total-elapsed-ms c)))
          (is (number? (:iter-1-closure-rate c)))
          (is (number? (:iter-2-closure-rate c))))))))

(deftest verify-aggregate-timing-handles-missing-elapsed-ms
  (testing "elapsed-ms is optional — missing values don't break aggregate"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "ok" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          ;; No elapsed-ms supplied — :elapsed-ms must be nil, not 0.
          (is (nil? (-> pf :per-attempt first :elapsed-ms)))
          ;; Total elapsed should be nil when no attempts carry timing.
          (is (nil? (-> v :counts :total-elapsed-ms))))))))

(deftest verify-iter-2-happy-path
  (testing "Iter-1 fails, iter-2 closes — report shows 2 attempts, outcome :closed"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "first try failed"
                    :attempt 1 :elapsed-ms 1000}
                   {:stable-id "x/foo" :report "second try succeeded"
                    :attempt 2 :elapsed-ms 1500}]]
      ;; Post-verify: finding gone.
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (is (= :closed (:outcome pf)))
          (is (= 2 (:attempts pf)))
          (is (= 2 (count (:per-attempt pf))))
          (is (= 1.0 (-> v :counts :iter-2-closure-rate))
              "1 finding with 2 attempts that closed → 100% iter-2 closure rate"))))))

(deftest render-shows-attempts-and-hint-marker
  (testing ":rendered surfaces attempts count + canvas-side-hint marker"
    (let [plan {:plan [{:stable-id "x/SomeInvariant"
                        :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {:canvas-kind :invariant :attempt 1}
                        :batch-key "src/x.clj"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/SomeInvariant"
                    :report "not yet implemented" :attempt 1}
                   {:stable-id "x/SomeInvariant"
                    :report "TODO placeholder" :attempt 2}]
          still-there [{:check :inspect.drift/missing-implementation
                        :severity :warning
                        :offenders [{:stable-id "x/SomeInvariant"
                                     :expected-code-path "src/x.clj"
                                     :expected-symbol "some-invariant?"
                                     :canvas-kind :invariant}]}]]
      (with-redefs [api/canvas-drift (fn [& _] still-there)]
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (str/includes? (:rendered v) "attempts: 2"))
          (is (str/includes? (:rendered v) "canvas-side-hint"))
          (is (str/includes? (:rendered v) "Attempts:")))))))

;; ---------------------------------------------------------------------------
;; Phase 9 Sprint 2 Task 5b — Stale-plan heuristic

(deftest verify-stale-plan-throws-when-reports-stable-ids-absent-from-plan
  (testing "Reports referencing stable-ids the plan doesn't carry → :stale-plan"
    (let [;; Stale shape: plan is empty (canvas-author re-ran plan AFTER
          ;; dispatch and findings were closed), but they still hold reports
          ;; from the original pre-dispatch plan.
          fresh-plan {:plan []
                      :unhandled []
                      :scope {:module-coord "distributed.cluster"}
                      :max-attempts 2}
          stale-reports [{:stable-id "distributed.cluster/MajorityRequiredForLeadership"
                          :report "I wrote the test" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [thrown (try
                       (api/close-drift-verify :plan fresh-plan
                                               :reports stale-reports)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              ":stale-plan should throw on this shape")
          (is (= :stale-plan (-> thrown ex-data :type)))
          (is (= 1 (-> thrown ex-data :reports-count)))
          (is (= {:module-coord "distributed.cluster"}
                 (-> thrown ex-data :plan-scope)))
          (is (= ["distributed.cluster/MajorityRequiredForLeadership"]
                 (-> thrown ex-data :unmatched-report-stable-ids))))))))

(deftest verify-stale-plan-no-throw-when-reports-match-plan
  (testing "Reports stable-ids match plan stable-ids → no stale-plan throw"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id "x/foo" :report "done" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        ;; Should not throw — plan recognises the report's stable-id.
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (= :closed (-> v :per-finding first :outcome))))))))

(deftest verify-stale-plan-no-throw-on-empty-reports
  (testing "Empty reports + any plan → no stale-plan throw (legitimate edge case)"
    (let [empty-plan {:plan []
                      :unhandled []
                      :scope {:module-coord "x"}
                      :max-attempts 2}]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        ;; Should not throw — no reports to mismatch.
        (let [v (api/close-drift-verify :plan empty-plan :reports [])]
          (is (= [] (:per-finding v))))))))

(deftest verify-stale-plan-no-throw-when-partial-overlap
  (testing "At least one report stable-id matches the plan → not stale (partial overlap is acceptable; the unmatched reports get dropped silently — they have no plan entry to classify)"
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          ;; One report matches, one doesn't.
          reports [{:stable-id "x/foo" :report "done" :attempt 1}
                   {:stable-id "x/bar" :report "stray" :attempt 1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        ;; Should NOT throw — at least one report matches plan entries.
        (let [v (api/close-drift-verify :plan plan :reports reports)]
          (is (= 1 (count (:per-finding v))))
          (is (= :closed (-> v :per-finding first :outcome))))))))

;; ---------------------------------------------------------------------------
;; Sprint 6 — Regression: invariant property-test findings populate the
;; structured :expected-code-path on the plan entry. The bug: when Sprint 5
;; added `expected-path-for` projection-kind branching in
;; `fukan.canvas.inspect.drift`, the structured `:offenders[0]
;; :expected-code-path` had to track the test/-rooted path for
;; `:projection-kind/property-test` findings. If it ever regresses to
;; `src/<ns>.clj`, the closure controller's :batches grouping drops all
;; invariant entries into the wrong file bucket.

(deftest plan-invariant-finding-carries-test-side-expected-code-path
  (testing "Synthetic invariant drift with projection-kind/property-test → plan entry's :expected-code-path is test/-rooted"
    (let [synthetic [{:check     :inspect.drift/missing-implementation
                      :severity  :warning
                      :message   "synthetic invariant"
                      :offenders [{:stable-id          "distributed.cluster/MajorityRequiredForLeadership"
                                   :expected-code-path "test/fukan/distributed/cluster_test.clj"
                                   :expected-symbol    "majority-required-for-leadership-property"
                                   :canvas-kind        :invariant
                                   :projection-kind    :projection-kind/property-test}]}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p     (api/close-drift-plan :module-coord "distributed.cluster")
              entry (first (:plan p))]
          (is (= 1 (count (:plan p))))
          (is (= "test/fukan/distributed/cluster_test.clj"
                 (-> entry :context :expected-code-path))
              (str "structured :expected-code-path on the plan entry must be the test/-rooted path; "
                   "if this falls back to src/ the closure controller's :batches mis-groups invariant entries."))
          (is (= "test/fukan/distributed/cluster_test.clj" (:batch-key entry))
              ":batch-key derives from :expected-code-path; same regression surface")
          (is (= ["test/fukan/distributed/cluster_test.clj"]
                 (keys (:batches p)))
              ":batches grouping keys on the test-side path"))))))

(deftest plan-invariant-finding-from-live-drift-test-side-path
  (testing "End-to-end: live (canvas-drift) over distributed.cluster yields invariant plan entries on test/-rooted paths"
    (let [p (api/close-drift-plan :module-coord "distributed.cluster" :limit 25)
          invariant-entries (filter #(= :invariant (-> % :context :canvas-kind))
                                    (:plan p))]
      (when (seq invariant-entries)
        (doseq [e invariant-entries]
          (is (str/starts-with? (-> e :context :expected-code-path) "test/")
              (str "invariant plan entry " (:stable-id e)
                   " should target a test/ file; got " (-> e :context :expected-code-path)))
          (is (str/ends-with? (-> e :context :expected-code-path) "_test.clj"))
          (is (= (-> e :context :expected-code-path) (:batch-key e))))))))

;; ---------------------------------------------------------------------------
;; Phase 9 Sprint 3 Task 8 — stress-test coverage for the three reserved
;; escalation triggers Sprint 4 of Phase 8 implemented but didn't exercise
;; empirically:
;;   - :scenario-not-found        (8.1) — plan→verify chain through
;;                                  :unhandled
;;   - :dispatch-error            (8.2) — :error true boolean shape
;;                                  (separate codepath from the string
;;                                  shape covered by
;;                                  `escalation-trigger-dispatch-error`)
;;   - :projection-emits-warning  (8.3) — decision: leave reserved.
;;                                  Today no projection emits :warnings;
;;                                  these tests pin the reserved state so
;;                                  the trigger doesn't accidentally
;;                                  start firing from a stray
;;                                  classification branch.

(deftest sprint-3-task-8-1-scenario-not-found-full-plan-verify-chain
  (testing "Full plan→verify chain for an unknown drift kind surfaces :scenario-not-found via the :unhandled-entries path"
    ;; This is a sibling to `escalation-trigger-scenario-not-found`. The
    ;; sibling asserts the broader contract: the synthetic finding flows
    ;; from plan's :unhandled into verify's :per-finding (via
    ;; unhandled-entries), is counted as escalated, and carries a
    ;; structured :escalation-reason with the right :trigger + :detail.
    (let [synthetic [{:check     :inspect.drift/this-kind-is-not-registered
                      :severity  :warning
                      :message   "synthetic finding for Sprint 3 Task 8.1"
                      :offenders [{:stable-id          "synthetic.module/Whatever"
                                   :expected-code-path "src/synthetic/module.clj"
                                   :expected-symbol    "Whatever"
                                   :canvas-kind        :function}]
                      :detail    {}}]]
      (with-redefs [api/canvas-drift (fn [& _] synthetic)]
        (let [p (api/close-drift-plan)]
          (testing "plan extracts the finding into :unhandled with :scenario-not-found"
            (is (= 0 (count (:plan p))))
            (is (= 1 (count (:unhandled p))))
            (is (= :scenario-not-found (-> p :unhandled first :reason))))
          (let [v  (api/close-drift-verify :plan p :reports [])
                pf (-> v :per-finding first)]
            (testing "verify lifts the unhandled entry into :per-finding"
              (is (= 1 (count (:per-finding v))))
              (is (= "synthetic.module/Whatever" (:stable-id pf)))
              (is (= :failed (:outcome pf))))
            (testing "structured :escalation-reason carries :scenario-not-found"
              (let [er (:escalation-reason pf)]
                (is (map? er))
                (is (= :scenario-not-found (:trigger er)))
                (is (string? (:detail er)))))
            (testing ":counts reflects the entry as escalated"
              (is (= 1 (-> v :counts :findings-total)))
              (is (= 1 (-> v :counts :findings-failed)))
              (is (= 1 (-> v :counts :findings-escalated))))))))))

(deftest sprint-3-task-8-2-dispatch-error-from-error-true-boolean
  (testing ":dispatch-error fires when the report carries :error true (boolean, separate branch from :error <string>)"
    ;; Existing `escalation-trigger-dispatch-error` exercises the
    ;; :error "Agent timeout" string shape. `dispatch-error-report?`
    ;; accepts both (true? :error) and (string? :error). This test
    ;; pins the boolean branch — the exact shape the task contract
    ;; calls out: {:stable-id ... :error true ...}.
    (let [plan {:plan [{:stable-id "x/foo" :scenario :code-side/drift-close
                        :check :inspect.drift/missing-implementation
                        :rendered "…" :code-spec {}
                        :context {} :batch-key "p"}]
                :unhandled []
                :scope {:module-coord "x"}
                :max-attempts 2}
          reports [{:stable-id    "x/foo"
                    :report       nil
                    :error        true
                    :error-reason "Agent dispatch failed"
                    :attempt      1}]]
      (with-redefs [api/canvas-drift (fn [& _] [])]
        (let [v  (api/close-drift-verify :plan plan :reports reports)
              pf (-> v :per-finding first)]
          (testing "outcome is :failed and not retried (dispatch failure is not a retry case)"
            (is (= :failed (:outcome pf)))
            (is (false? (:requires-retry? pf))))
          (testing ":escalation-reason classifies the boolean :error as :dispatch-error"
            (let [er (:escalation-reason pf)]
              (is (map? er))
              (is (= :dispatch-error (:trigger er)))
              (is (string? (:detail er)))
              (is (str/includes? (:detail er) "x/foo"))))
          (testing ":counts reflects the failed dispatch"
            (is (= 1 (-> v :counts :findings-failed)))
            (is (= 1 (-> v :counts :findings-escalated)))))))))

(deftest sprint-3-task-8-3-projection-emits-warning-stays-reserved
  (testing ":projection-emits-warning trigger is reserved — no classification path fires it today (Sprint 3 decision: leave reserved)"
    ;; Decision (Phase 9 Sprint 3 Task 8.3, option a): leave the trigger
    ;; reserved. Layer A projections in
    ;; src/fukan/canvas/project/clojure/*.clj emit no :warnings vector.
    ;; Inventing a synthetic warning surface purely to fire the trigger
    ;; creates noise without representing real Layer A behaviour.
    ;;
    ;; This test pins the reserved state in two ways:
    ;;   1. The docstring on `close-drift-verify` still names the trigger
    ;;      as reserved (so the public contract advertises it).
    ;;   2. A plan entry carrying a `:warnings` vector does NOT cause the
    ;;      classifier to emit `:projection-emits-warning` — confirming
    ;;      no stray branch fires before Layer A grows a warning surface.
    (testing "docstring on close-drift-verify advertises the reserved trigger"
      (let [doc (or (-> #'api/close-drift-verify meta :agent/doc) "")]
        (is (str/includes? doc ":projection-emits-warning"))
        (is (str/includes? doc "reserved"))))
    (testing "a plan entry with synthetic :warnings does not fire :projection-emits-warning today"
      (let [still-there [{:check    :inspect.drift/missing-implementation
                          :severity :warning
                          :offenders [{:stable-id          "x/foo"
                                       :expected-code-path "p"
                                       :expected-symbol    "foo"}]}]
            plan {:plan [{:stable-id "x/foo"
                          :scenario  :code-side/drift-close
                          :check     :inspect.drift/missing-implementation
                          :rendered  "…"
                          :code-spec {}
                          :context   {}
                          :batch-key "p"
                          ;; Synthetic future-shape: Layer A would attach
                          ;; warnings here if/when it grows a fall-back
                          ;; surface.
                          :warnings  [{:kind :synthetic
                                       :detail "would-fire-if-classified"}]}]
                  :unhandled []
                  :scope {:module-coord "x"}
                  :max-attempts 1}
            reports [{:stable-id "x/foo" :report "first" :attempt 1}]]
        (with-redefs [api/canvas-drift (fn [& _] still-there)]
          (let [v  (api/close-drift-verify :plan plan :reports reports)
                pf (-> v :per-finding first)
                er (:escalation-reason pf)]
            (is (some? er)
                "the finding is still escalated — exhausted attempts triggers it, NOT :projection-emits-warning")
            (is (not= :projection-emits-warning (:trigger er))
                ":projection-emits-warning must not fire today; reserved for a future Layer A warning surface")))))))
