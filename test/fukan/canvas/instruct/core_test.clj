(ns fukan.canvas.instruct.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.instruct.core :as core]))

;; ---------------------------------------------------------------------------
;; Scenario declaration contract

(def ^:private minimal-valid-scenario
  {:scenario-id     :code-side/example
   :description     "An example scenario."
   :prompt-fragment "Situational framing prose."
   :build-context   (fn [_spec _opts] {})
   :render          (fn [_spec _ctx _opts]
                      {:scenario-id :code-side/example
                       :code-spec   {}
                       :scenario-context {}
                       :rendered    "x"})})

(deftest valid-scenario-passes
  (is (core/valid-scenario? minimal-valid-scenario))
  (is (= [] (core/validate-scenario minimal-valid-scenario))))

(deftest non-map-fails
  (is (not (core/valid-scenario? "not-a-map")))
  (is (seq (core/validate-scenario "not-a-map"))))

(deftest scenario-id-must-be-qualified-keyword
  (testing "simple keyword is rejected (must be namespaced)"
    (let [m (assoc minimal-valid-scenario :scenario-id :drift-close)]
      (is (not (core/valid-scenario? m)))
      (is (some #(re-find #":scenario-id" %)
                (core/validate-scenario m))))))

(deftest missing-description-fails
  (let [m (dissoc minimal-valid-scenario :description)]
    (is (not (core/valid-scenario? m)))
    (is (some #(re-find #":description" %)
              (core/validate-scenario m)))))

(deftest missing-prompt-fragment-fails
  (let [m (dissoc minimal-valid-scenario :prompt-fragment)]
    (is (not (core/valid-scenario? m)))
    (is (some #(re-find #":prompt-fragment" %)
              (core/validate-scenario m)))))

(deftest missing-build-context-fails
  (let [m (dissoc minimal-valid-scenario :build-context)]
    (is (not (core/valid-scenario? m)))
    (is (some #(re-find #":build-context" %)
              (core/validate-scenario m)))))

(deftest missing-render-fails
  (let [m (dissoc minimal-valid-scenario :render)]
    (is (not (core/valid-scenario? m)))
    (is (some #(re-find #":render" %)
              (core/validate-scenario m)))))

(deftest non-fn-build-context-fails
  (let [m (assoc minimal-valid-scenario :build-context "not-a-fn")]
    (is (not (core/valid-scenario? m)))))

(deftest non-fn-render-fails
  (let [m (assoc minimal-valid-scenario :render "not-a-fn")]
    (is (not (core/valid-scenario? m)))))

;; ---------------------------------------------------------------------------
;; Per-instruction return shape contract

(def ^:private minimal-valid-instruction
  {:scenario-id      :code-side/example
   :code-spec        {:projection-kind :clojure/example
                      :lens-id         :clojure
                      :model-element-kind :Type
                      :model-element-id "m/type/X"
                      :target {:path "x.clj" :namespace "x" :symbol "X"}
                      :template "(def X :x)"
                      :prose "doc"}
   :scenario-context {}
   :rendered         "# instruction\n\ncontent"})

(deftest valid-instruction-passes
  (is (core/valid-instruction? minimal-valid-instruction))
  (is (= [] (core/validate-instruction minimal-valid-instruction))))

(deftest instruction-scenario-id-must-be-qualified
  (let [m (assoc minimal-valid-instruction :scenario-id :flat)]
    (is (not (core/valid-instruction? m)))))

(deftest instruction-missing-rendered-fails
  (let [m (dissoc minimal-valid-instruction :rendered)]
    (is (not (core/valid-instruction? m)))
    (is (some #(re-find #":rendered" %)
              (core/validate-instruction m)))))

(deftest instruction-rendered-must-be-string
  (let [m (assoc minimal-valid-instruction :rendered 42)]
    (is (not (core/valid-instruction? m)))))

(deftest instruction-scenario-context-must-be-map
  (let [m (assoc minimal-valid-instruction :scenario-context "not-a-map")]
    (is (not (core/valid-instruction? m)))))
