(ns fukan.cozo.mirror-test
  "Datom parity — the P1 oracle foundation: every datascript datom of the real
   held model round-trips through the Cozo mirror, by value-type relation, with
   nothing dropped."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            ;; composition root — registers the extractor so build-model "src"
            ;; merges the extracted code graph onto the design graph
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]))

(defn- all-rows [cdb name]
  (set (db/q cdb (str "?[e, a, v] := *" name "[e, a, v]"))))

(deftest mirror-round-trips-every-datom
  (testing "every datom of the held model appears in its value-type Cozo relation"
    (let [ds       (pipeline/build-model "src")
          expected (mirror/rows-by-bucket ds)
          cdb      (mirror/mirror ds)]
      (try
        (doseq [[bucket {:keys [name]}] mirror/relations]
          (is (= (get expected bucket) (all-rows cdb name))
              (str name " mirrors its datascript datoms exactly")))
        (testing "completeness — no datom dropped across the partition"
          (let [total-ds (count (d/datoms ds :eavt))
                total-cz (reduce + (for [{:keys [name]} (vals mirror/relations)]
                                     (count (all-rows cdb name))))]
            (is (= total-ds total-cz))))
        (finally (db/close cdb))))))

(deftest mirror-round-trips-a-synthetic-db
  (testing "the value-type partition handles int / string / keyword / bool / compound"
    (let [conn (d/create-conn {:ref {:db/valueType :db.type/ref}})]
      (d/transact! conn [{:db/id -1 :entity/name "a" :val/extracted true :rel/kind :calls}
                         {:db/id -2 :entity/name "b" :val/order 7 :ref -1}])
      (let [ds  @conn
            cdb (mirror/mirror ds)]
        (try
          (is (= (count (d/datoms ds :eavt))
                 (reduce + (for [{:keys [name]} (vals mirror/relations)]
                             (count (all-rows cdb name))))))
          ;; spot-check: the keyword value mirrored as a colon-less string
          (is (some (fn [[_ a v]] (and (= a "rel/kind") (= v "calls")))
                    (all-rows cdb "t_str")))
          (finally (db/close cdb)))))))
