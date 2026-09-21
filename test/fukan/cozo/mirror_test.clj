(ns fukan.cozo.mirror-test
  "The substrate WRITE layer's storage contract — what `load-datoms` leaves behind besides the
   rows. The rows themselves are asserted everywhere else in this suite; what is only visible
   here is the `(a, v)` index, which changes no answer and so has no other test that would
   notice it going missing."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.query :as cq]))

(defn- av-index
  "The `(a, v)` seek prefix of `rel`'s secondary index, as the engine reports it: a vector of
   column positions over `{e a v}`, so `[1 2 0]` reads \"a, then v, then e as the payload\".
   nil when the relation carries no index at all."
  [cdb rel]
  (some-> (seq (db/q cdb (str "::indices " rel))) first last :indices))

(deftest every-typed-relation-is-indexed-on-attribute-and-value
  (testing "each bucket carries a secondary index whose seek prefix is (a, v)"
    (let [cdb (mirror/load-datoms [[1 :entity/name "one"] [1 :rel/from 2] [1 :val/private true]])]
      (try
        (doseq [rel ["t_str" "t_int" "t_bool"]]
          (is (= [1 2 0] (av-index cdb rel))
              (str rel " seeks on (a, v) with the entity as payload")))
        (finally (db/close cdb))))))

(deftest a-bucket-that-loads-empty-is-indexed-too
  (testing "the index is created per relation, not per relation that happened to receive rows"
    ;; `insert-datoms` fills a bucket after the load — the grammar reflection grounds Int and
    ;; Bool datoms onto a db built from a canvas that carried none.
    (let [cdb (mirror/load-datoms [[1 :entity/name "only-strings"]])]
      (try
        (is (= [1 2 0] (av-index cdb "t_int")))
        (is (= [1 2 0] (av-index cdb "t_bool")))
        (finally (db/close cdb))))))

(deftest a-datom-inserted-after-the-load-is-still-found-by-an-attribute-value-probe
  (testing "insert-datoms maintains the index the load built"
    (let [cdb (mirror/load-datoms [[1 :structure/of :probe/Thing]])]
      (try
        (mirror/insert-datoms cdb [[2 :structure/of :probe/Thing] [3 :structure/of :probe/Other]])
        (is (= #{1 2} (set (cq/q '[:find [?e ...] :where [?e :structure/of :probe/Thing]] cdb)))
            "the probe reads the loaded row and the inserted one alike")
        (finally (db/close cdb))))))

(deftest a-write-after-the-first-query-is-visible-to-the-next
  (testing "`query/buckets-of` memoizes attr→bucket per db handle, and a clause compiles to the ONE
            bucket that index names. The handle does not change when `insert-datoms` writes, so a
            memo keyed on the handle alone goes stale — and stale is not merely slow. An attribute
            the index already files under one bucket, then written at another type, compiles to a
            read of the old bucket only: the new datom is silently absent from every answer.
            (An attribute the index has never seen falls back to the three-way union — correct,
            but a rule rather than a keyed read; that half cost Region's grounded membership its
            key on brian.)"
    (let [cdb (mirror/load-datoms [[1 :val/x 5]])]
      (try
        (is (= #{[1 5]} (cq/q '[:find ?e ?v :where [?e :val/x ?v]] cdb))
            "precondition: the first query files :val/x under t_int")
        (mirror/insert-datoms cdb [[2 :val/x "five"] [3 :val/y true]])
        (is (= #{[1 5] [2 "five"]} (cq/q '[:find ?e ?v :where [?e :val/x ?v]] cdb))
            "the same attribute at a second type is read from both buckets")
        (is (= #{"t_bool"} (get (cq/buckets-of cdb) "val/y"))
            "and an attribute first seen after the memo is indexed, not left to the union fallback")
        (finally (db/close cdb))))))
