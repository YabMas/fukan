(ns tasks.vsdd
  "VSDD orchestration: runs phases 2-5 (Tester -> Builder -> Adversary -> Judge)
   for each module task in a task file.

   Usage: bb vsdd:run tasks.edn"
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Config

(def ^:private max-iterations 3)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- timestamp []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))

(defn- module-slug
  "src/fukan/projection/ -> projection"
  [module-path]
  (-> module-path
      (str/replace #"/$" "")
      (str/split #"/")
      last))

(defn- ensure-dir [path]
  (.mkdirs (io/file path)))

(defn- print-phase [phase-num phase-name module]
  (println)
  (println (str "=== Phase " phase-num ": " phase-name " (" module ") ==="))
  (println))


;; ---------------------------------------------------------------------------
;; REPL check

(defn- repl-available? []
  (let [result (p/sh "clj-nrepl-eval" "--discover-ports")]
    (and (zero? (:exit result))
         (str/includes? (:out result) "7889"))))

;; ---------------------------------------------------------------------------
;; Agent invocation

(defn- format-stream-line
  "Parse a stream-json line and return a human-readable string, or nil to skip."
  [line agent-name]
  (try
    (let [evt (json/parse-string line true)]
      (case (:type evt)
        "assistant"
        (let [content (get-in evt [:message :content])]
          (->> content
               (keep (fn [block]
                       (case (:type block)
                         "text"     (str "  [" agent-name "] " (:text block))
                         "tool_use" (str "  [" agent-name "] -> " (:name block))
                         nil)))
               (str/join "\n")
               not-empty))

        "result"
        (str "  [" agent-name "] done")

        ;; skip system, rate_limit, tool_result etc
        nil))
    (catch Exception _ nil)))

(defn- invoke-agent
  "Spawn a claude -p subprocess with an agent definition.
   Streams progress to terminal via stream-json parsing.
   Returns {:exit int :result string}."
  [agent-name module-path run-dir prompt]
  (let [env (merge (into {} (System/getenv))
                   {"MODULE_PATH" module-path
                    "AGENT_ROLE" agent-name
                    "VSDD_RUN_DIR" run-dir})
        env (dissoc env "CLAUDECODE")
        proc (p/process {:env env
                         :err :inherit
                         :in ""}
                        "claude" "-p" prompt
                        "--agent" agent-name
                        "--permission-mode" "bypassPermissions"
                        "--verbose"
                        "--output-format" "stream-json")
        ;; Read stdout in a thread: parse stream-json, print progress, collect result
        reader (future
                 (let [result-text (atom "")]
                   (with-open [rdr (io/reader (:out proc))]
                     (loop []
                       (when-let [line (.readLine rdr)]
                         ;; Check for result event to capture final text
                         (try
                           (let [evt (json/parse-string line true)]
                             (when (= "result" (:type evt))
                               (reset! result-text (str (:result evt)))))
                           (catch Exception _))
                         ;; Print human-readable progress
                         (when-let [msg (format-stream-line line agent-name)]
                           (println msg)
                           (flush))
                         (recur))))
                   @result-text))]
    @proc
    {:exit (:exit @proc)
     :result (deref reader 5000 "")}))

(defn- invoke-judge
  "Lightweight LLM call for classification (no tools).
   Streams output and returns the result text."
  [prompt]
  (let [env (-> (into {} (System/getenv)) (dissoc "CLAUDECODE"))
        proc (p/process {:env env
                         :err :inherit
                         :in ""}
                        "claude" "-p" prompt
                        "--model" "haiku"
                        "--tools" ""
                        "--verbose"
                        "--output-format" "stream-json")
        reader (future
                 (let [result-text (atom "")]
                   (with-open [rdr (io/reader (:out proc))]
                     (loop []
                       (when-let [line (.readLine rdr)]
                         (try
                           (let [evt (json/parse-string line true)]
                             (when (= "result" (:type evt))
                               (reset! result-text (str (:result evt))))
                             (when-let [msg (format-stream-line line "judge")]
                               (println msg)
                               (flush)))
                           (catch Exception _))
                         (recur))))
                   @result-text))]
    @proc
    (deref reader 5000 "")))

;; ---------------------------------------------------------------------------
;; Prompt builders

(defn- build-tester-prompt
  ([task] (build-tester-prompt task nil))
  ([{:keys [module context spec-refs description]} feedback]
   (str "MODULE: " module "\n"
        "\n"
        (when (seq context)
          (str "CONTEXT:\n"
               (str/join "\n" (map #(str "- " %) context))
               "\n\n"))
        (when (seq spec-refs)
          (str "SPEC_REFS:\n"
               (str/join "\n" (map #(str "- " %) spec-refs))
               "\n\n"))
        "DESCRIPTION: " description
        (when feedback
          (str "\n\n"
               "FEEDBACK FROM PREVIOUS ITERATION (fix these issues):\n"
               "---\n"
               feedback "\n"
               "---")))))

(defn- build-builder-prompt
  ([task] (build-builder-prompt task nil))
  ([{:keys [module context description]} feedback]
   (str "MODULE: " module "\n"
        "\n"
        (when (seq context)
          (str "CONTEXT:\n"
               (str/join "\n" (map #(str "- " %) context))
               "\n\n"))
        "DESCRIPTION: " description "\n"
        "\n"
        "Find all stub functions (those throwing UnsupportedOperationException) "
        "and implement them until all tests pass."
        (when feedback
          (str "\n\n"
               "FEEDBACK FROM PREVIOUS ITERATION (fix these issues):\n"
               "---\n"
               feedback "\n"
               "---")))))

(defn- build-adversary-prompt [{:keys [module description spec-refs]} run-dir iteration]
  (str "MODULE: " module "\n"
       "RUN_DIR: " run-dir "\n"
       "\n"
       "DESCRIPTION: " description "\n"
       "\n"
       (when (seq spec-refs)
         (str "SPEC_REFS (scope your review to these rules ONLY):\n"
              (str/join "\n" (map #(str "- " %) spec-refs))
              "\n\n"))
       "Review the spec, tests, and implementation for this module. "
       "IMPORTANT: Only check alignment for the spec rules listed above. "
       "Do NOT review unrelated rules or functions in the same module. "
       "Check all three alignments (spec<->test, test<->impl, spec<->impl). "
       "Write your structured report to <RUN_DIR>/" (module-slug module) "/adversary-report-" iteration ".edn"))

(defn- build-judge-prompt [report-edn {:keys [module description spec-refs]} iteration]
  (str "DO NOT use any tools. Just read this prompt and respond with a verdict.\n"
       "\n"
       "You are the VSDD judge. You review an adversary report and decide the next action.\n"
       "\n"
       "Module: " module "\n"
       "Description: " description "\n"
       (when (seq spec-refs)
         (str "In-scope spec rules: " (str/join ", " spec-refs) "\n"))
       "Iteration: " iteration " of " max-iterations "\n"
       "\n"
       "IMPORTANT: Only consider findings related to the spec rules listed above. "
       "Ignore any findings about unrelated rules or functions in the same module. "
       "If all IN-SCOPE findings are resolved, verdict is CONVERGED even if out-of-scope issues remain.\n"
       "\n"
       "Adversary report:\n"
       "---\n"
       report-edn "\n"
       "---\n"
       "\n"
       "Respond with EXACTLY one of these verdicts on the FIRST LINE (no other text on line 1):\n"
       "CONVERGED\n"
       "ROUTE_TO_SPEC\n"
       "ROUTE_TO_TEST\n"
       "ROUTE_TO_IMPL\n"
       "\n"
       "Then explain your reasoning briefly on subsequent lines."))

;; ---------------------------------------------------------------------------
;; Verdict parsing

(defn- parse-verdict
  "Extract verdict keyword from judge output.
   Looks for CONVERGED/ROUTE_TO_* on the first line."
  [output]
  (let [first-line (first (str/split-lines output))]
    (cond
      (str/includes? first-line "CONVERGED")      :converged
      (str/includes? first-line "ROUTE_TO_SPEC")  :route-to-spec
      (str/includes? first-line "ROUTE_TO_TEST")  :route-to-test
      (str/includes? first-line "ROUTE_TO_IMPL")  :route-to-impl
      :else                                       :unknown)))

;; ---------------------------------------------------------------------------
;; Module cycle

(defn- read-adversary-report [run-dir module-path iteration]
  (let [slug (module-slug module-path)
        report-file (io/file run-dir slug (str "adversary-report-" iteration ".edn"))]
    (when (.exists report-file)
      (slurp report-file))))

(defn- run-module-cycle
  "Run the tester->builder->adversary->judge loop for a single module task."
  [task run-dir]
  (let [module (:module task)]
    (loop [iteration 1
           feedback nil]
      (when (> iteration max-iterations)
        (println)
        (println (str "CIRCUIT BREAKER: Module " module " did not converge after "
                      max-iterations " iterations."))
        (println "  Human review required. Check the adversary reports in:" run-dir)
        (throw (ex-info "Circuit breaker: max iterations reached"
                        {:module module :iterations max-iterations})))

      (println)
      (println (str "---- Iteration " iteration "/" max-iterations " for " module " ----"))

      ;; Phase 2: Tester
      (print-phase 2 "Tester" module)
      (let [tester-result (invoke-agent "tester" module run-dir
                                        (build-tester-prompt task feedback))]
        (when-not (zero? (:exit tester-result))
          (throw (ex-info "Tester agent failed" {:module module :exit (:exit tester-result)})))

        ;; Phase 3: Builder
        (print-phase 3 "Builder" module)
        (let [builder-result (invoke-agent "builder" module run-dir
                                           (build-builder-prompt task feedback))]
          (when-not (zero? (:exit builder-result))
            (throw (ex-info "Builder agent failed" {:module module :exit (:exit builder-result)})))

          ;; Phase 4: Adversary
          (print-phase 4 "Adversary" module)
          (let [slug-dir (str run-dir "/" (module-slug module))]
            (ensure-dir slug-dir)
            (let [adversary-result (invoke-agent "adversary" module run-dir
                                                 (build-adversary-prompt task run-dir iteration))]
              (when-not (zero? (:exit adversary-result))
                (throw (ex-info "Adversary agent failed" {:module module :exit (:exit adversary-result)})))

              ;; Phase 5: Judge
              (print-phase 5 "Judge" module)
              (let [report-edn (or (read-adversary-report run-dir module iteration)
                                   "{:findings [] :verdict :converged}")
                    judge-output (invoke-judge (build-judge-prompt report-edn task iteration))
                    judge-verdict (parse-verdict judge-output)]
                (println (str "  Judge verdict: " (name judge-verdict)))
                (println judge-output)
                (println)

                (case judge-verdict
                  :converged
                  (do
                    (println (str "Module " module " converged on iteration " iteration))
                    :converged)

                  :route-to-spec
                  (do
                    (println (str "Module " module " needs spec clarification -- pausing for human review"))
                    (println "  Check adversary report:" (str run-dir "/" (module-slug module) "/adversary-report.edn"))
                    :route-to-spec)

                  :route-to-test
                  (do
                    (println "  Routing to test -- re-running from tester phase")
                    (recur (inc iteration) judge-output))

                  :route-to-impl
                  (do
                    (println "  Routing to impl -- re-running builder+adversary+judge")
                    ;; TODO: optimize to skip tester on route-to-impl
                    (recur (inc iteration) judge-output))

                  ;; Unknown verdict
                  (do
                    (println (str "Unknown judge verdict: " judge-verdict " -- treating as needs-review"))
                    :unknown))))))))))

;; ---------------------------------------------------------------------------
;; Main entry point

(defn run
  "Run VSDD phases 2-5 for all tasks in a task file."
  [tasks-file]
  (when (str/blank? tasks-file)
    (throw (ex-info "Usage: bb vsdd:run <tasks.edn>" {})))

  (when-not (.exists (io/file tasks-file))
    (throw (ex-info (str "Task file not found: " tasks-file) {})))

  ;; Parse task file
  (let [task-data (edn/read-string (slurp tasks-file))
        tasks (:tasks task-data)]
    (when (empty? tasks)
      (throw (ex-info "No tasks found in task file" {:file tasks-file})))

    ;; Verify REPL
    (print "Checking REPL availability... ")
    (if (repl-available?)
      (println "OK (port 7889)")
      (do
        (println "FAILED")
        (throw (ex-info "nREPL not available on port 7889. Start it first." {}))))

    ;; Create run directory
    (let [run-id (timestamp)
          run-dir (str ".vsdd/" run-id)]
      (ensure-dir run-dir)

      (println)
      (println "=== VSDD Run ===")
      (println "  Run ID: " run-id)
      (println "  Intent: " (or (:intent task-data) "n/a"))
      (println "  Tasks:  " (count tasks))
      (println "  Run dir:" run-dir)

      ;; Run each module task sequentially
      (let [results (doall
                      (for [task tasks]
                        (try
                          {:module (:module task)
                           :result (run-module-cycle task run-dir)}
                          (catch Exception e
                            {:module (:module task)
                             :result :error
                             :error (ex-message e)}))))]

        ;; Final summary
        (println)
        (println "=== VSDD Summary ===")
        (doseq [{:keys [module result error]} results]
          (println (str "  " module ": "
                        (case result
                          :converged "CONVERGED"
                          :route-to-spec "NEEDS SPEC REVIEW"
                          :error (str "ERROR - " error)
                          (str result)))))
        (println)
        (println "Artifacts in:" run-dir)))))
