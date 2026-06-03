(ns fukan.target.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.target.clojure :as tc]))

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Operation per defn/defn-, with privacy"
    (let [db  (tc/extract "test/fixtures/target/sample.clj")
          ops (into {} (d/q '[:find ?n ?p
                              :where [?e :structure/of :Operation]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Operation; the def (gamma) is ignored; defn- is private"))))

(deftest operations-are-owned-by-their-module
  (testing "each namespace becomes a Module that owns its Operations (:module/child)"
    (let [db    (tc/extract "test/fixtures/target/sample.clj")
          owned (d/q '[:find ?mn ?on
                       :where [?m :structure/of :Module] [?m :entity/name ?mn]
                              [?m :module/child ?o] [?o :structure/of :Operation]
                              [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is a Module owning all three operations"))))
