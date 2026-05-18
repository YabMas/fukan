(ns fukan.model.expression-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest var-form
  (let [x (e/make-var "x")]
    (is (= :expr/var (get-in x [:form :case])))
    (is (= "x" (get-in x [:form :name])))
    (is (m/validate e/Expression x))))

(deftest ref-form
  (let [r (e/make-ref (t/make-ref-kernel-primitive #{:container}))]
    (is (= :expr/ref (get-in r [:form :case])))
    (is (m/validate e/Expression r))))

(deftest lit-form
  (let [v (e/make-lit (t/make-scalar "Integer") 42)]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= 42 (get-in v [:form :value])))))

(deftest apply-form
  (testing "Apply takes operator name + ordered args"
    (let [v (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])]
      (is (= "+" (get-in v [:form :op])))
      (is (= 2 (count (get-in v [:form :args])))))))

(deftest let-form
  (let [v (e/make-let "y" (e/make-var "x") (e/make-var "y"))]
    (is (= :expr/let (get-in v [:form :case])))))

(deftest if-form
  (let [v (e/make-if (e/make-var "p") (e/make-lit (t/make-scalar "Integer") 1)
                                       (e/make-lit (t/make-scalar "Integer") 0))]
    (is (= :expr/if (get-in v [:form :case])))))

(deftest forall-and-exists
  (let [f (e/make-forall "x" (e/make-var "S") (e/make-var "p"))
        x (e/make-exists "x" (e/make-var "S") (e/make-var "p"))]
    (is (= :expr/forall (get-in f [:form :case])))
    (is (= :expr/exists (get-in x [:form :case])))))

(deftest aggregate-form
  (let [v (e/make-aggregate :count (e/make-var "S") (e/make-var "x"))]
    (is (= :expr/aggregate (get-in v [:form :case])))
    (is (= :count (get-in v [:form :kind])))
    (is (m/validate e/Expression v))))

(deftest match-form
  (testing "Match arms each carry a TypePattern and a body"
    (let [v (e/make-match (e/make-var "v")
              [(e/make-match-arm {:case :pattern/scalar :name :literal/string} (e/make-lit (t/make-scalar "Integer") 1))
               (e/make-match-arm {:case :pattern/scalar :name :literal/integer} (e/make-lit (t/make-scalar "Integer") 0))])]
      (is (= :expr/match (get-in v [:form :case])))
      (is (= 2 (count (get-in v [:form :arms])))))))

(deftest label-not-in-identity
  (testing "Per K31, label? is addressability-only — not part of structural identity"
    (let [a (e/make-var "x")
          b (assoc a :label "guarantee-1")]
      (is (= (e/expression-identity a) (e/expression-identity b)))
      (is (not= a b)))))

(deftest identity-is-recursive
  (testing "Structural identity compares :form recursively, ignoring labels at every level"
    (let [a (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])
          b (assoc-in
              (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])
              [:form :args 0 :label] "named")
          c (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 2)])]
      (is (= (e/expression-identity a) (e/expression-identity b)))
      (is (not= (e/expression-identity a) (e/expression-identity c))))))

(deftest core-operators
  (testing "Kernel-core operator names are registered for documentation / discovery"
    (is (contains? e/core-operators "+"))
    (is (contains? e/core-operators "and"))
    (is (contains? e/core-operators "is-present"))))

(deftest environment-onestate
  (let [env (e/make-environment-onestate {"self" (t/make-ref-kernel-primitive #{:container})})]
    (is (= :env/onestate (:case env)))
    (is (m/validate e/Environment env))))

(deftest environment-twostate
  (let [env (e/make-environment-twostate
              {"X" (t/make-ref-kernel-primitive #{:container})}
              {"X" (t/make-ref-kernel-primitive #{:container})}
              {"order" (t/make-ref-kernel-primitive #{:container})})]
    (is (= :env/twostate (:case env)))
    (is (m/validate e/Environment env))))

(deftest environment-model-introspection
  (let [env (e/make-environment-model-introspection {})]
    (is (= :env/model-introspection (:case env)))
    (is (m/validate e/Environment env))))
