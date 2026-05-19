(ns fukan.constraint.derivations-extra-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.derivations-extra :as dx]
            [fukan.constraint.evaluator :as e]))

(deftest depends-on-base-case
  (let [edb {:edge #{["a" :relation/triggers "b"]}}
        result (e/evaluate-rules (dx/depends-on-rules) edb)]
    (is (contains? (:depends-on result) ["a" "b"]))))

(deftest depends-on-transitive
  (let [edb {:edge #{["a" :relation/triggers "b"]
                     ["b" :relation/realises "c"]}}
        result (e/evaluate-rules (dx/depends-on-rules) edb)]
    (is (contains? (:depends-on result) ["a" "b"]))
    (is (contains? (:depends-on result) ["b" "c"]))
    (is (contains? (:depends-on result) ["a" "c"]))))

(deftest depends-on-no-self-edge-by-default
  (let [edb {:edge #{["a" :relation/triggers "b"]}}
        result (e/evaluate-rules (dx/depends-on-rules) edb)]
    (is (not (contains? (:depends-on result) ["a" "a"])))
    (is (not (contains? (:depends-on result) ["b" "b"])))))

(deftest depends-on-detects-cycles
  ;; a → b → a; depends-on should produce [a a] and [b b].
  (let [edb {:edge #{["a" :relation/triggers "b"]
                     ["b" :relation/triggers "a"]}}
        result (e/evaluate-rules (dx/depends-on-rules) edb)]
    (is (contains? (:depends-on result) ["a" "a"]))
    (is (contains? (:depends-on result) ["b" "b"]))))
