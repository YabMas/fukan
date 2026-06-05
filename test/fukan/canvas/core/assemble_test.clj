(ns fukan.canvas.core.assemble-test
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]))

(s/defstructure* Thing "t")
(def t5-foo (Thing "foo"))     ; an instance-bearing var
(def t5-plain 42)              ; not an instance

(deftest collect-finds-instance-vars
  (let [found (a/collect ['fukan.canvas.core.assemble-test])
        names (set (map (fn [[v _]] (:name (meta v))) found))]
    (is (contains? names 't5-foo))
    (is (not (contains? names 't5-plain)))))

(s/defstructure* Box "box"
  (slot :links (one Box)))

(declare t6-b)
(def t6-a (Box "A" (links t6-b)))
(def t6-b (Box "B" (links t6-a)))

(deftest assembles-a-cycle-into-one-db
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])
        edges (d/q '[:find ?fn ?tn :where [?r :rel/from ?f] [?r :rel/to ?t]
                     [?f :entity/name ?fn] [?t :entity/name ?tn]] db)]
    (is (= #{["A" "B"] ["B" "A"]} (set edges)))
    (is (seq (d/q '[:find ?e :in $ ?id :where [?e :entity/id ?id]]
                  db "fukan.canvas.core.assemble-test/t6-a")))))
