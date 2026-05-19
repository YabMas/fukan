(ns fukan.project-layer.registry-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.project-layer.registry :as r]))

(deftest empty-registry-defaults
  (let [reg (r/make-registry)]
    (is (= "" (:root-prefix reg)))
    (is (= {} (:type-overrides reg)))
    (is (= [] (:idioms reg)))))

(deftest with-root-prefix
  (let [reg (r/with-root-prefix (r/make-registry) "myapp")]
    (is (= "myapp" (:root-prefix reg)))))

(deftest with-type-override
  (let [reg (-> (r/make-registry)
                (r/with-type-override "Money" [:and :int [:>= 0]])
                (r/with-type-override "Email" [:re #".+@.+"]))]
    (is (= [:and :int [:>= 0]] (-> reg :type-overrides (get "Money"))))
    (is (= 2 (count (:type-overrides reg))))))

(deftest with-idiom
  (let [reg (-> (r/make-registry)
                (r/with-idiom {:route {:primitive-kind :primitive/rule}
                               :body "use defmulti dispatch"}))]
    (is (= 1 (count (:idioms reg))))
    (is (= "use defmulti dispatch" (-> reg :idioms first :body)))))
