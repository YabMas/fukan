(ns fukan.canvas.lens.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.lens.core :as core]))

(def ^:private minimal-valid
  {:id              :test
   :description     "A test lens."
   :prompt-fragment "Frame the canvas through tests."
   :render          (fn [_findings _opts] "rendered")})

(deftest valid-lens-passes
  (testing "a minimal lens (no :compute) is valid"
    (is (core/valid-lens? minimal-valid))
    (is (= [] (core/validate-lens minimal-valid)))))

(deftest valid-lens-with-compute
  (testing "adding a :compute fn keeps the lens valid"
    (let [m (assoc minimal-valid :compute (fn [_db _opts] {:findings []}))]
      (is (core/valid-lens? m))
      (is (= [] (core/validate-lens m))))))

(deftest missing-id-fails
  (testing "absent :id is rejected"
    (let [m (dissoc minimal-valid :id)
          issues (core/validate-lens m)]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":id" %) issues)))))

(deftest non-keyword-id-fails
  (testing ":id must be a keyword"
    (let [m (assoc minimal-valid :id "patterns")
          issues (core/validate-lens m)]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":id" %) issues)))))

(deftest missing-description-fails
  (testing "absent :description is rejected"
    (let [m (dissoc minimal-valid :description)]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":description" %)
                (core/validate-lens m))))))

(deftest missing-prompt-fragment-fails
  (testing "absent :prompt-fragment is rejected"
    (let [m (dissoc minimal-valid :prompt-fragment)]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":prompt-fragment" %)
                (core/validate-lens m))))))

(deftest missing-render-fails
  (testing "absent :render is rejected"
    (let [m (dissoc minimal-valid :render)]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":render" %)
                (core/validate-lens m))))))

(deftest non-fn-compute-fails
  (testing ":compute, when present, must be a fn"
    (let [m (assoc minimal-valid :compute "not-a-fn")]
      (is (not (core/valid-lens? m)))
      (is (some #(re-find #":compute" %)
                (core/validate-lens m))))))

(deftest non-map-fails
  (testing "non-map input is rejected with a single issue"
    (is (not (core/valid-lens? "not-a-map")))
    (is (seq (core/validate-lens "not-a-map")))))
