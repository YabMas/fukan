(ns demos.grammar.grammar-test
  "Regression for the grammar demo: the modelled grammar builds and satisfies its
   laws; a planted ill-formed grammar is caught; and the Production layer captures
   alternation (many productions) vs ordered RHS sequence. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [demos.grammar.model.key-value :as kv]
            [demos.grammar.vocab.core :refer [Symbol Grammar]]))

(deftest key-value-grammar-is-well-formed
  (testing "the modelled key-value grammar builds and has no useless symbols"
    (is (empty? (s/check (kv/build))))))

(deftest unreachable-symbol-is-caught
  (testing "a symbol unreachable from the start trips the reachability law"
    (let [db (s/with-structures
               (s/within-module "g"
                 (Symbol "IDENT")
                 (Symbol "root" (produces (Production (rhs [IDENT]))))
                 (Symbol "orphan")                       ; in the grammar, but unreachable
                 (Grammar "g"
                   (start root)
                   (symbol root) (symbol IDENT) (symbol orphan))))]
      (is (some #(= "every symbol is reachable from the start symbol" (:law %))
                (s/check db))))))

(deftest production-rhs-is-ordered
  (testing "a production's :rhs records symbol order via :rel/order"
    (let [db (kv/build)]
      (is (= ["key" "COLON" "value"]
             (->> (d/q '[:find ?o ?n
                         :where [?p :entity/name "pair"]
                                [?rp :rel/from ?p] [?rp :rel/kind :produces] [?rp :rel/to ?prod]
                                [?rr :rel/from ?prod] [?rr :rel/kind :rhs] [?rr :rel/order ?o]
                                [?rr :rel/to ?s] [?s :entity/name ?n]]
                       db)
                  (sort-by first) (mapv second)))
          "pair's RHS is the ordered sequence [key COLON value]"))))

(deftest alternation-is-multiple-productions
  (testing "value has two Productions (STRING | NUMBER), distinct from an ordered sequence"
    (let [db (kv/build)]
      (is (= 2 (count (d/q '[:find ?prod
                             :where [?v :entity/name "value"]
                                    [?r :rel/from ?v] [?r :rel/kind :produces] [?r :rel/to ?prod]]
                           db)))
          "two alternatives → two Production nodes"))))
