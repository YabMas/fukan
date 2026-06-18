(ns fukan.canvas.core.assemble-test
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]))

(s/defstructure Thing "t")
(Thing ^{:name "foo"} t5-foo)  ; an instance-bearing var
(def t5-plain 42)              ; not an instance

(deftest assemble-includes-only-instance-vars
  ;; var discovery (`collect`) is a private internal — exercise it through the public `assemble`.
  (let [db    (a/assemble ['fukan.canvas.core.assemble-test])
        names (set (map first (d/q '[:find ?n :where [?e :entity/name ?n]] db)))]
    (is (contains? names "foo") "the instance-bearing var t5-foo (^{:name \"foo\"}) becomes a node")
    (is (not (contains? names "t5-plain")) "the plain (def t5-plain 42) is skipped")))

(s/defstructure Box "box" {:links Box})

(declare t6-b)
(Box ^{:name "A"} t6-a {:links t6-b})
(Box ^{:name "B"} t6-b {:links t6-a})

(deftest assembles-a-cycle-into-one-db
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])
        edges (d/q '[:find ?fn ?tn :where [?r :rel/from ?f] [?r :rel/to ?t]
                     [?f :structure/of ::Box]
                     [?f :entity/name ?fn] [?t :entity/name ?tn]] db)]
    (is (= #{["A" "B"] ["B" "A"]} (set edges)))
    (is (seq (d/q '[:find ?e :in $ ?id :where [?e :entity/id ?id]]
                  db "fukan.canvas.core.assemble-test/t6-a")))))

;; ── Task 7: value dedup, ordered, labelled ────────────────────────────────────

;; value dedup (inline + top-level)
(s/defstructure ^:value Tag2 "tag" {:n :string})
(s/defstructure Holder "h" {:a Tag2 :b Tag2})
(Holder ^{:name "h"} t7-h {:a (Tag2 {:n "x"}) :b (Tag2 {:n "x"})})   ; two equal inline values
(def t7-v1 (Tag2 {:n "z"}))                                          ; two equal top-level values
(def t7-v2 (Tag2 {:n "z"}))

;; ordered
(s/defstructure Sym2 "s")
(s/defstructure Seq2 "seq" {:items [:* Sym2]})
(Sym2 ^{:name "x"} t7-sx) (Sym2 ^{:name "y"} t7-sy)
(Seq2 ^{:name "sq"} t7-seq {:items [t7-sx t7-sy]})

;; labelled
(s/defstructure Node2 "n")
(s/defstructure Edge2 "e" {:to Node2})
(Node2 ^{:name "n"} t7-n)
(Edge2 ^{:name "e"} t7-e {:to [knows t7-n]})

(deftest value-dedup-inline
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of ::Tag2] [?e :val/n "x"]] db))))))

(deftest value-dedup-top-level
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of ::Tag2] [?e :val/n "z"]] db))))))

(deftest ordered-emits-rel-order
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])
        orders (d/q '[:find ?o ?nm :where [?r :rel/kind :items] [?r :rel/order ?o]
                      [?r :rel/to ?t] [?t :entity/name ?nm]] db)]
    (is (= #{[0 "x"] [1 "y"]} (set orders)))))

(deftest labelled-emits-rel-label
  (let [db (a/assemble ['fukan.canvas.core.assemble-test])]
    (is (= #{["knows"]} (set (d/q '[:find ?l :where [?r :rel/kind :to] [?r :rel/label ?l]] db))))))
