(ns fukan.canvas.lens.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.lens.core :as core]
            [fukan.canvas.lens.registry :as registry]))

;; Two synthetic lens vars used to white-box test the registry mechanism.
;; The production `known-lenses` registry is exercised by the lens-specific
;; integration tests (see fukan.canvas.lens.patterns-test etc.) — here we
;; only verify the registry surface honors the contract.

(def ^:private fake-a
  {:id              :fake-a
   :description     "Fake lens A."
   :prompt-fragment "frame-a"
   :render          (fn [_findings _opts] "rendered-a")})

(def ^:private fake-b
  {:id              :fake-b
   :description     "Fake lens B."
   :prompt-fragment "frame-b"
   :compute         (fn [_db _opts] {:b-findings true})
   :render          (fn [findings _opts]
                      (str "rendered-b:" (boolean (:b-findings findings))))})

(def ^:private broken
  {:id          :broken
   :description "missing required keys"})

;; ---------------------------------------------------------------------------
;; Tests against synthetic lens vars by redefining the private known-lenses.
;; ---------------------------------------------------------------------------

(deftest empty-registry-returns-empty
  (testing "with no registered lenses, all-lenses returns an empty seq"
    (with-redefs [registry/known-lenses []]
      (is (empty? (registry/all-lenses)))
      (is (nil? (registry/lens-by-id :anything))))))

(deftest all-lenses-returns-registered-valid-lenses
  (testing "all-lenses returns every valid registered lens"
    (with-redefs [registry/known-lenses [#'fake-a #'fake-b]]
      (let [lenses (registry/all-lenses)]
        (is (= 2 (count lenses)))
        (is (every? core/valid-lens? lenses))
        (is (= #{:fake-a :fake-b} (set (map :id lenses))))))))

(deftest lens-by-id-finds-registered
  (testing "lens-by-id resolves a registered lens id"
    (with-redefs [registry/known-lenses [#'fake-a #'fake-b]]
      (is (= :fake-a (:id (registry/lens-by-id :fake-a))))
      (is (= :fake-b (:id (registry/lens-by-id :fake-b)))))))

(deftest lens-by-id-unknown-returns-nil
  (testing "lens-by-id returns nil for an unregistered id"
    (with-redefs [registry/known-lenses [#'fake-a]]
      (is (nil? (registry/lens-by-id :no-such-lens))))))

(deftest invalid-lens-var-is-skipped-with-warning
  (testing "an invalid lens in the known-lenses list is skipped with a stderr warning, not thrown"
    (with-redefs [registry/known-lenses [#'broken #'fake-a]]
      (let [err (java.io.StringWriter.)
            lenses (binding [*err* err]
                     (vec (registry/all-lenses)))]
        (is (= [:fake-a] (mapv :id lenses))
            "the broken lens must be filtered out; the valid one remains")
        (is (re-find #"invalid lens" (str err))
            "a warning must be printed to *err*")))))
