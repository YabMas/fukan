(ns fukan.canvas.projection.probe-code-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.assemble :as a]
            [canvas.domain.probe :refer [Finding]]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probe-code :as pc]))

;; a Finding with no probe yielding it → trips the "every finding is yielded by some
;; probe" law (a broken model for the integrity probe to surface)
(def orphan-finding (Finding "Orphan" (gating false)))

(deftest projects-uniform-observations-contract
  (testing "the projected contract checks a result's observations are {focus tag note}"
    (let [db    (cs/build)
          pred  (eval (:contract-form (pc/project-probe db "integrity")))]
      (is (pred {:observations []}) "empty observations satisfy the contract")
      (is (pred {:observations [{:focus #{1} :as :violation :note "x"}]}))
      (is (not (pred {:observations [{:focus [1] :as :violation :note "x"}]}))
          ":focus must be a set")
      (is (not (pred {:observations [{:focus #{1} :as "violation" :note "x"}]}))
          ":as must be a keyword")
      (is (not (pred {:observations "nope"})) ":observations must be sequential"))))

(deftest projects-composing-probe-fn-form
  (testing "project-probe emits an fn that composes check into violation observations"
    (let [db   (cs/build)
          form (:fn-form (pc/project-probe db "integrity"))
          fdg  ((eval form) db)]
      (is (= 'fn (first form)))
      (is (= '[target-db] (second form)))
      (is (= "integrity" (:lens fdg)))
      (is (true? (:gating fdg)))
      (is (empty? (:observations fdg)) "the clean self-model yields no violations"))))

(deftest projected-integrity-probe-surfaces-real-violations
  (testing "over a broken model the projected probe yields violation observations"
    (let [db    (cs/build)
          probe (eval (:fn-form (pc/project-probe db "integrity")))
          dirty (a/assemble-vars [#'orphan-finding])
          fdg   (probe dirty)]
      (is (seq (:observations fdg)) "violations are reported")
      (is (every? #(= :violation (:as %)) (:observations fdg)))
      (is (every? #(set? (:focus %)) (:observations fdg)) "each carries an offender focus"))))

(deftest projects-fresh-probe-as-instruction
  (testing "a fresh probe (no :calls) projects to an Instruction describing observations"
    (let [db (cs/build)
          {:keys [instruction contract-form fn-form]} (pc/project-probe db "patterns")]
      (is (nil? fn-form) "a fresh probe emits no mechanical fn-form")
      (is (string? instruction))
      (is (re-find #"probe-patterns" instruction))
      (is (re-find #"recurring structures across the model" instruction) "carries the lens focus")
      (is (re-find #":observations" instruction) "describes the observations payload")
      (is (re-find #":gating false" instruction) "carries the modelled gating")
      (is (re-find #"no recurring structures yields no reported patterns" instruction) "carries the holds")
      (let [valid? (eval contract-form)]
        (is (valid? {:observations [{:focus #{1} :as :pattern :note "x"}]}))
        (is (not (valid? {:observations [{:focus #{1} :as :pattern :note 1}]})))))))

(deftest composing-probe-still-projects-fn-form
  (testing "integrity (composing) is unchanged in flavour — fn-form, no instruction"
    (let [db (cs/build)
          {:keys [fn-form instruction]} (pc/project-probe db "integrity")]
      (is (nil? instruction))
      (is (= 'fn (first fn-form))))))
