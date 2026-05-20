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

(deftest evaluate-against-edb
  (testing "find primitives of kind :primitive/behaviour"
    (let [edb {:primitive/kind #{["behaviour:a" :primitive/behaviour]
                                  ["behaviour:b" :primitive/behaviour]
                                  ["container:x" :primitive/container]}}
          parsed (q/parse '[:find ?p
                            :where [?p :primitive/kind :primitive/behaviour]])
          rows (q/evaluate parsed edb)]
      (is (= 2 (count rows)))
      (is (every? #(string? (get % :?p)) rows)))))

(deftest evaluate-with-join
  (testing "join two atoms via shared variable"
    (let [edb {:primitive/kind  #{["b1" :primitive/behaviour]
                                   ["b2" :primitive/behaviour]
                                   ["c1" :primitive/container]}
               :primitive/owner #{["b1" "c1"]
                                   ["b2" "c1"]}}
          parsed (q/parse '[:find ?p ?m
                            :where
                              [?p :primitive/kind :primitive/behaviour]
                              [?p :primitive/owner ?m]])
          rows (q/evaluate parsed edb)]
      (is (= 2 (count rows)))
      (is (every? #(and (:?p %) (:?m %)) rows)))))
