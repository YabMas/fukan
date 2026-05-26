(ns fukan.canvas.core.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.substrate.store :as store]))

(deftest store-creation
  (testing "creates an empty store"
    (let [s (store/create)]
      (is (some? s))
      (is (empty? (store/all-modules s))))))

(deftest transact-module
  (testing "adds a Module and finds it"
    (let [s (-> (store/create)
                (store/transact! (sub/module "accounts")))]
      (is (= 1 (count (store/all-modules s))))
      (is (= "accounts" (-> s store/all-modules first :name))))))

(deftest transact-affordance-with-module
  (testing "affordances-in finds Affordances via :module/child on the owning Module"
    (let [m   (sub/module "accounts")
          a   (sub/affordance "create")
          mid (sub/id-of m)
          s   (-> (store/create)
                  (store/transact! m)
                  (store/transact! a)
                  (d/db-with [[:db/add [:entity/id mid]
                                       :module/child
                                       [:entity/id (sub/id-of a)]]]))]
      (is (= 1 (count (store/affordances-in s mid)))))))

(deftest store-persists-affordance-doc
  (testing ":affordance/doc lands in the store"
    (let [m (sub/module "accounts")
          a (sub/affordance "find-by-email"
              :doc "Look up an account.")
          s (-> (store/create)
                (store/transact! m)
                (store/transact! a))]
      (is (= [["find-by-email" "Look up an account."]]
             (vec (d/q '[:find ?n ?doc
                         :where [?e :entity/name ?n]
                                [?e :affordance/doc ?doc]]
                       s)))))))

(deftest store-persists-type-doc
  (testing ":type/doc lands in the store"
    (let [t (sub/type-record "Account" [["email" {:kind :atomic :name :String}]]
              :doc "Account record.")
          s (-> (store/create)
                (store/transact! t))]
      (is (= [["Account" "Account record."]]
             (vec (d/q '[:find ?n ?doc
                         :where [?e :entity/name ?n]
                                [?e :type/doc ?doc]]
                       s)))))))

(deftest children-of-module-query
  (testing "children-of-module returns [type name] pairs for all module children"
    (let [m   (sub/module "accounts")
          a   (sub/affordance "create")
          t   (sub/type-record "Account" [])
          mid (sub/id-of m)
          s   (-> (store/create)
                  (store/transact! m)
                  (store/transact! a)
                  (store/transact! t)
                  (d/db-with [[:db/add [:entity/id mid] :module/child [:entity/id (sub/id-of a)]]
                               [:db/add [:entity/id mid] :module/child [:entity/id (sub/id-of t)]]]))]
      (is (= #{[:Affordance "create"] [:Type "Account"]}
             (set (store/children-of-module s mid)))))))

(deftest affordance-input-types-are-queryable
  (testing "an affordance with cross-module-typed inputs has those types as queryable datoms"
    (let [m (sub/module "evaluator")
          a (sub/affordance "evaluate_rules"
              :shape {:kind :arrow
                      :inputs {:kind :record
                               :fields [["rules" {:kind :list :elem {:kind :ref :target :ast/ConstraintRule}}]
                                        ["edb" {:kind :ref :target :derivations/EDB}]]}
                      :outputs {:kind :ref :target :derivations/EDB}})
          db (-> (store/create) (store/transact! m) (store/transact! a))]
      (is (= #{:ast/ConstraintRule :derivations/EDB}
             (set (map first (d/q '[:find ?t :where [?a :affordance/input-types ?t]] db)))))
      (is (= #{:derivations/EDB}
             (set (map first (d/q '[:find ?t :where [?a :affordance/output-types ?t]] db))))))))

(deftest affordance-without-shape-has-no-type-attrs
  (testing "a no-shape Affordance (invariant/rule) does NOT get input/output type datoms"
    (let [m (sub/module "constraint.evaluator")
          a (sub/affordance "StratifiedFixedPoint"
              :formal-expression "Always converges.")
          db (-> (store/create) (store/transact! m) (store/transact! a))]
      (is (empty? (d/q '[:find ?t :where [?a :affordance/input-types ?t]] db)))
      (is (empty? (d/q '[:find ?t :where [?a :affordance/output-types ?t]] db))))))

(deftest type-field-types-are-queryable
  (testing "a record Type with cross-module-typed fields has those types as queryable datoms"
    (let [t (sub/type-record "Phase4Result"
              [["model" {:kind :ref :target :model/Model}]
               ["violations" {:kind :list :elem {:kind :ref :target :agent/Violation}}]])
          db (-> (store/create) (store/transact! t))]
      (is (= #{:model/Model :agent/Violation}
             (set (map first (d/q '[:find ?ft :where [?ty :type/field-types ?ft]] db))))))))

(deftest atomic-type-has-no-field-types
  (testing "a Type with :kind :atomic has no :type/field-types"
    (let [t (sub/type-primitive "Stratum")
          db (-> (store/create) (store/transact! t))]
      (is (empty? (d/q '[:find ?ft :where [?ty :type/field-types ?ft]] db))))))
