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
  (testing "an Affordance references its containing Module by id"
    (let [m (sub/module "accounts")
          a (sub/affordance "create" :module (sub/id-of m))
          s (-> (store/create)
                (store/transact! m)
                (store/transact! a))]
      (is (= 1 (count (store/affordances-in s (sub/id-of m))))))))

(deftest store-persists-affordance-doc
  (testing ":affordance/doc lands in the store"
    (let [m (sub/module "accounts")
          a (sub/affordance "find-by-email"
              :module (sub/id-of m)
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
