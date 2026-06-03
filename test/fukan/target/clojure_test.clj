(ns fukan.target.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.target.clojure :as tc]))

(deftest extracts-defns-as-code-structures
  (testing "the trivial extractor emits a Defn per defn, with arity"
    (let [db (tc/extract "test/fixtures/target/sample.clj")
          defns (into {} (d/q '[:find ?n ?a
                                :where [?e :structure/of :Defn] [?e :entity/name ?n] [?e :val/arity ?a]]
                              db))]
      (is (= {"alpha" 1 "beta" 2} defns)
          "both defns extracted with correct arity; the def (gamma) is ignored"))))
