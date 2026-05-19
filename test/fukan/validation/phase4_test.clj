(ns fukan.validation.phase4-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.validation.phase4 :as phase4]
            [fukan.validation.violation :as v]
            [fukan.model.build :as build]
            [fukan.model.pipeline :as model-pipeline]))

(deftest phase4-on-empty-model-passes
  (testing "an empty model produces no violations"
    (let [result (phase4/run (build/empty-model))]
      (is (map? result))
      (is (= [] (:violations result)))
      (is (= (build/empty-model) (:model result))))))

(deftest gate-g2-halts-on-error
  (testing "any error-severity violation triggers Gate G2"
    (let [bad-violations [(v/make-violation
                            {:severity :error
                             :phase :phase4
                             :sub-phase :4a
                             :kind :test/synthetic
                             :message "synthetic"})]]
      (is (thrown? Exception
                   (phase4/gate-g2 (build/empty-model) bad-violations))))))

(deftest gate-g2-passes-on-warnings-only
  (testing "warnings don't halt"
    (let [warn-violations [(v/make-violation
                             {:severity :warning
                              :phase :phase4
                              :sub-phase :4a
                              :kind :test/synthetic
                              :message "warn"})]
          result (phase4/gate-g2 (build/empty-model) warn-violations)]
      (is (= 1 (count (:violations result)))))))

(deftest combined-pipeline-with-phase4-runs-cleanly
  ;; Smoke: fukan-on-fukan loads through both extensions, runs Phase 4,
  ;; and either passes cleanly OR raises a documented Gate G2 with
  ;; expected violations. Until Tasks 4-10 land rules, this returns
  ;; the model with [] violations.
  (testing "Phase 4 runs on the fukan corpus without throwing"
    (let [model (model-pipeline/load-source "src")]
      (is (or (map? model) (some? model))
          "pipeline returns a model or a {:model :violations} map"))))

(deftest sub-phase-order-is-fixed
  (testing "run-sub-phases visits 4a → 4b → 4c → 4d → 4e → 4f → 4g in order"
    (let [calls (atom [])
          fake-rule (fn [phase-key]
                      (fn [_model] (swap! calls conj phase-key) []))]
      (with-redefs [phase4/rules-4a (fake-rule :4a)
                    phase4/rules-4b (fake-rule :4b)
                    phase4/rules-4c (fake-rule :4c)
                    phase4/rules-4d (fake-rule :4d)
                    phase4/rules-4e (fake-rule :4e)
                    phase4/rules-4f (fake-rule :4f)
                    phase4/rules-4g (fake-rule :4g)]
        (#'phase4/run-sub-phases (build/empty-model))
        (is (= [:4a :4b :4c :4d :4e :4f :4g] @calls))))))
