(ns fukan.canvas.core.assemble-test
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]))

(s/defstructure Thing "t")
(def t5-foo (Thing "foo"))     ; an instance-bearing var
(def t5-plain 42)              ; not an instance

(deftest collect-finds-instance-vars
  (let [found (a/collect ['fukan.canvas.core.assemble-test])
        names (set (map (fn [[v _]] (:name (meta v))) found))]
    (is (contains? names 't5-foo))
    (is (not (contains? names 't5-plain)))))

(s/defstructure Box "box"
  (slot :links (one Box)))

(declare t6-b)
(def t6-a (Box "A" (links t6-b)))
(def t6-b (Box "B" (links t6-a)))

(deftest assembles-a-cycle-into-one-db
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])
        edges (d/q '[:find ?fn ?tn :where [?r :rel/from ?f] [?r :rel/to ?t]
                     [?f :structure/of :Box]
                     [?f :entity/name ?fn] [?t :entity/name ?tn]] db)]
    (is (= #{["A" "B"] ["B" "A"]} (set edges)))
    (is (seq (d/q '[:find ?e :in $ ?id :where [?e :entity/id ?id]]
                  db "fukan.canvas.core.assemble-test/t6-a")))))

;; ── Task 7: value dedup, ordered, labelled ────────────────────────────────────

;; value dedup (inline + top-level)
(s/defstructure ^:value Tag2 "tag" (slot :n (one :String)))
(s/defstructure Holder "h" (slot :a (one Tag2)) (slot :b (one Tag2)))
(def t7-h  (Holder "h" (a (Tag2 (n "x"))) (b (Tag2 (n "x")))))   ; two equal inline values
(def t7-v1 (Tag2 (n "z")))                                         ; two equal top-level values
(def t7-v2 (Tag2 (n "z")))

;; ordered
(s/defstructure Sym2 "s")
(s/defstructure Seq2 "seq" (slot :items (ordered Sym2)))
(def t7-sx (Sym2 "x")) (def t7-sy (Sym2 "y"))
(def t7-seq (Seq2 "sq" (items [t7-sx t7-sy])))

;; labelled
(s/defstructure Node2 "n")
(s/defstructure Edge2 "e" (slot :to (one Node2)))
(def t7-n (Node2 "n"))
(def t7-e (Edge2 "e" (to [knows t7-n])))

(deftest value-dedup-inline
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :Tag2] [?e :val/n "x"]] db))))))

(deftest value-dedup-top-level
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :Tag2] [?e :val/n "z"]] db))))))

(deftest ordered-emits-rel-order
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])
        orders (d/q '[:find ?o ?nm :where [?r :rel/kind :items] [?r :rel/order ?o]
                      [?r :rel/to ?t] [?t :entity/name ?nm]] db)]
    (is (= #{[0 "x"] [1 "y"]} (set orders)))))

(deftest labelled-emits-rel-label
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= #{["knows"]} (set (d/q '[:find ?l :where [?r :rel/kind :to] [?r :rel/label ?l]] db))))))
