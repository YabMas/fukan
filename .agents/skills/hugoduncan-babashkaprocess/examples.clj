#!/usr/bin/env bb

;; babashka.process examples
;; Run with: bb examples.clj

(ns examples
  (:require [babashka.process :refer [shell process check sh $ tokenize
                                      alive? destroy pb pipeline]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(println "=== babashka.process examples ===\n")

;;; Basic shell usage

(println "--- shell: basic command ---")
(shell "echo" "Hello from shell")

(println "\n--- shell: auto-tokenization ---")
(shell "echo hello world")

(println "\n--- shell: capture output ---")
(let [result (shell {:out :string} "echo" "captured")]
  (println "Captured:" (str/trim (:out result))))

(println "\n--- shell: working directory ---")
(shell {:dir "/tmp"} "pwd")

;;; process function

(println "\n--- process: non-blocking ---")
(let [p (process {:out :string} "echo" "from process")]
  (println "Process started, waiting...")
  (println "Output:" (str/trim (:out @p)))
  (println "Exit code:" (:exit @p)))

(println "\n--- process: check exit code ---")
(try
  (->> (process {:out :string} "echo" "checked")
       check
       :out
       str/trim
       (println "Checked output:"))
  (catch Exception e
    (println "Error:" (ex-message e))))

;;; sh convenience function

(println "\n--- sh: string output by default ---")
(let [{:keys [exit out]} (sh "echo" "from sh")]
  (println "Exit:" exit "Output:" (str/trim out)))

;;; $ macro

(println "\n--- $: macro syntax ---")
(let [name "world"]
  ($ echo Hello ~name))

;;; tokenize

(println "\n--- tokenize: split arguments ---")
(println "Tokenized:" (tokenize "ls -la /tmp"))
(println "With quotes:" (tokenize "echo 'hello world'"))

;;; Environment variables

(println "\n--- shell: extra environment ---")
(shell {:extra-env {"GREETING" "Hello"}} "bash" "-c" "echo $GREETING")

;;; Error handling

(println "\n--- shell: continue on error ---")
(let [{:keys [exit]} (shell {:continue true} "ls" "/nonexistent-path-12345")]
  (println "Exit code (expected non-zero):" exit))

(println "\n--- shell: capture stderr ---")
(let [{:keys [err]} (shell {:err :string :continue true} "ls" "/nonexistent-path-12345")]
  (println "Stderr captured:" (not (str/blank? err))))

;;; Input to process

(println "\n--- process: string input ---")
(let [result @(process {:in "line1\nline2\nline3" :out :string} "wc" "-l")]
  (println "Line count:" (str/trim (:out result))))

;;; Piping processes

(println "\n--- process: piping ---")
(let [result (->> (process {:out :string} "echo" "-e" "apple\nbanana\ncherry")
                  deref
                  :out)]
  (println "Echo result:" (str/replace result "\n" " ")))

;;; Process control

(println "\n--- process: alive? and destroy ---")
(let [p (process "sleep" "10")]
  (println "Alive after start:" (alive? p))
  (destroy p)
  (Thread/sleep 100)
  (println "Alive after destroy:" (alive? p)))

;;; Pre-start hook

(println "\n--- shell: pre-start-fn ---")
(shell {:pre-start-fn (fn [{:keys [cmd]}]
                        (println "About to run:" cmd))}
       "echo" "hooked")

;;; Pipeline (JDK9+)

(println "\n--- pipeline: chained processes ---")
(let [result (-> (pipeline (pb "echo" "one\ntwo\nthree")
                           (pb "grep" "t")
                           (pb "wc" "-l"))
                 last
                 deref
                 :out
                 slurp
                 str/trim)]
  (println "Pipeline result:" result))

;;; Write to file

(println "\n--- shell: write to file ---")
(let [tmp-file (str (System/getProperty "java.io.tmpdir") "/bb-process-test.txt")]
  (shell {:out :write :out-file tmp-file} "echo" "written to file")
  (println "File contents:" (str/trim (slurp tmp-file)))
  (io/delete-file tmp-file))

;;; Merge stderr to stdout

(println "\n--- shell: merge stderr to stdout ---")
(let [{:keys [out]} (shell {:err :out :out :string :continue true}
                           "bash" "-c" "echo stdout; echo stderr >&2")]
  (println "Merged output:" (str/trim out)))

(println "\n=== All examples completed ===")
