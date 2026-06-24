(ns fukan.cozo.db-test
  "Smoke test for the Cozo engine seam: the native lib loads, a CozoScript query
   runs in-process, and a graph-algorithm fixed rule is available (the migration
   relies on `ConnectedComponents`/`SCC`/`DegreeCentrality`)."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.db :as cozo]))

(deftest engine-runs-a-constant-query
  (testing "a trivial CozoScript query returns its rows"
    (cozo/with-db
      (fn [db]
        (is (= [[1 2] [3 4]] (cozo/q db "?[a, b] <- [[1, 2], [3, 4]]")))))))

(deftest graph-algo-fixed-rules-are-available
  (testing "the build is compiled with the graph-algo feature (ConnectedComponents)"
    (cozo/with-db
      (fn [db]
        ;; two disjoint edges → two components; assert the partition, not the ids
        (let [rows  (cozo/q db "edges[a, b] <- [[1,2],[3,4]]
                                ?[n, c] <~ ConnectedComponents(edges[a, b])")
              comps (->> rows (group-by second) vals (map #(set (map first %))) set)]
          (is (= #{#{1 2} #{3 4}} comps)))))))

(deftest a-failed-query-throws
  (testing "q surfaces a query error rather than returning a bad result"
    (cozo/with-db
      (fn [db]
        (is (thrown? clojure.lang.ExceptionInfo
                     (cozo/q db "?[x] <- [[ this is not valid cozoscript")))))))
