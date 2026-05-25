(ns fukan.canvas.defconstructor-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.defconstructor :refer [defconstructor]]
            [fukan.canvas.helpers :as h]))

(defconstructor test-lift
  "A test lift with intent and target."
  (form intent  "The intent payload."   :shape :field+ :required true)
  (form target  "The target value."     :shape :value-ref :required true)

  (produces [name doc forms]
    (h/declare-affordance name
      :shape (h/arrow (h/record-of (:intent forms)) :Unit)
      :role :test-role)))

(deftest invocation-creates-substrate
  (testing "invoking a lift produces substrate inside with-canvas"
    (h/with-canvas
      (h/within-module "test-mod"
        (test-lift "do-thing"
          "Do the thing."
          (intent [email :String])
          (target SomeValue))))
    ;; If we got here without throwing, the lift produced substrate
    (is true)))

(deftest required-form-missing-errors
  (testing "missing a required form raises a diagnostic error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"required form `intent` missing"
                          (h/with-canvas
                            (h/within-module "test-mod"
                              (test-lift "do-thing"
                                "Doc."
                                (target SomeValue))))))))

(deftest unknown-form-errors
  (testing "an unknown form raises a diagnostic error listing available forms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"`returns` is not a body form of `test-lift`"
                          (h/with-canvas
                            (h/within-module "test-mod"
                              (test-lift "do-thing"
                                "Doc."
                                (intent [x :String])
                                (target Y)
                                (returns :Boolean))))))))
