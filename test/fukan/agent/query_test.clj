(ns fukan.agent.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.agent.query :as q]))

(deftest parse-simple-find-where
  (testing "single find var, single where atom"
    (let [parsed (q/parse '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])]
      (is (= [:?p] (:find parsed)))
      (is (= 1 (count (:where parsed))))
      (let [atm (first (:where parsed))]
        (is (= :primitive/kind (:predicate atm)))
        (is (= [:?p :primitive/behaviour] (:args atm)))))))

(deftest parse-multi-where
  (testing "multiple where atoms"
    (let [parsed (q/parse '[:find ?p ?m
                             :where
                               [?p :primitive/kind :primitive/behaviour]
                               [?p :primitive/owner ?m]])]
      (is (= [:?p :?m] (:find parsed)))
      (is (= 2 (count (:where parsed)))))))

(deftest parse-rejects-malformed
  (testing "missing :where raises"
    (is (thrown? clojure.lang.ExceptionInfo (q/parse '[:find ?p])))))
