(ns fukan.canvas.core.substrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.substrate :as sub]))

(deftest module-construction
  (testing "a Module has a name and an id"
    (let [m (sub/module "accounts")]
      (is (= "accounts" (sub/name-of m)))
      (is (some? (sub/id-of m)))
      (is (= :Module (sub/primitive-kind m))))))

(deftest affordance-construction
  (testing "an Affordance has name, optional shape/role/formal-expression; no :module field"
    (let [a (sub/affordance "create-account"
                            :shape {:inputs :unit :outputs :unit}
                            :role :exposed-call)]
      (is (= "create-account" (sub/name-of a)))
      (is (= :exposed-call (sub/role-of a)))
      (is (= :Affordance (sub/primitive-kind a))))))

(deftest state-construction
  (testing "a State has name and required shape; no :module field"
    (let [ty (sub/type-primitive :String)
          s (sub/state "config" :shape (sub/id-of ty))]
      (is (= "config" (sub/name-of s)))
      (is (= :State (sub/primitive-kind s))))))

(deftest relation-construction
  (testing "a Relation is a directed triple with optional tags"
    (let [a (sub/affordance "x")
          b (sub/affordance "y")
          r (sub/relation (sub/id-of a) :canvas/calls (sub/id-of b))]
      (is (= (sub/id-of a) (sub/from-of r)))
      (is (= :canvas/calls (sub/kind-of r)))
      (is (= (sub/id-of b) (sub/to-of r))))))

(deftest tag-construction
  (testing "a Tag applies to an entity"
    (let [m (sub/module "accounts")
          t (sub/apply-tag m :Deprecated)]
      (is (contains? (sub/tags-of t) :Deprecated)))))

(deftest affordance-doc-persists
  (testing "an affordance constructed with :doc has its doc persisted"
    (let [a (sub/affordance "find-by-email"
              :doc "Look up an account by email address.")]
      (is (= "Look up an account by email address." (sub/doc-of a))))))

(deftest type-doc-persists
  (testing "a type constructed with :doc has its doc persisted"
    (let [t (sub/type-record "Account" [["email" {:kind :atomic :name :String}]]
              :doc "An owned account record.")]
      (is (= "An owned account record." (sub/doc-of t))))))
