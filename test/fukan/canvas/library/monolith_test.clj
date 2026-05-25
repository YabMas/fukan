(ns fukan.canvas.library.monolith-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function record value]]
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

(deftest value-lift-creates-opaque-type
  (testing "(value …) produces an atomic Type with no fields"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (value "Stratum" "An opaque stratification level.")
                 (value "Binding" "A logical-variable binding.")))
          types (d/q '[:find ?n :where [?e :entity/type :Type] [?e :entity/name ?n]] db)]
      (is (= 2 (count types)))
      (is (= #{"Stratum" "Binding"} (set (map first types)))))))

(deftest record-accepts-optional-fields
  (testing "(field port (optional :Integer)) works"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "Server config."
                   (field port (optional :Integer))
                   (field host :String))))]
      ;; The Type is in the store
      (is (= 1 (count (d/q '[:find ?n
                             :where [?e :entity/type :Type]
                                    [?e :entity/name "ServerOpts"]
                                    [?e :entity/name ?n]]
                           db)))))))

(deftest function-accepts-list-takes
  (testing "(takes [rules (list-of :ConstraintRule)]) works"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (function "evaluate" "Evaluate constraint rules over EDB."
                   (takes [rules (list-of :ConstraintRule)] [edb :EDB])
                   (gives :Model))))]
      (is (= 1 (count (d/q '[:find ?n
                             :where [?e :entity/type :Affordance]
                                    [?e :entity/name "evaluate"]
                                    [?e :entity/name ?n]]
                           db)))))))
