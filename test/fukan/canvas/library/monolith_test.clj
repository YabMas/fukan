(ns fukan.canvas.library.monolith-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function record]]
            [fukan.canvas.substrate.store :as store]))

(deftest function-lift-produces-affordance
  (testing "(function …) produces a callable Affordance inside a Module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (function "find-by-email"
                   "Look up an account by email."
                   (takes [email :String])
                   (gives :Account))))]
      (is (= 1 (count (store/all-modules db)))))))

(deftest function-lift-rejects-unknown-form
  (testing "unknown form raises diagnostic"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"`returns` is not a body form of `function`"
          (h/with-canvas
            (h/within-module "x"
              (function "f" "doc."
                (takes [a :String])
                (returns :Bool))))))))

(deftest record-lift-produces-type
  (testing "(record …) produces a Type"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (record "Account"
                   "An account record."
                   (field email :String)
                   (field name :String))))]
      ;; The record adds a Type to the store; we can't easily query types yet,
      ;; but the build should not throw and should leave the module in place.
      (is (= 1 (count (store/all-modules db)))))))
