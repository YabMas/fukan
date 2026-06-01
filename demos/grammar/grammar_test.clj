(ns demos.grammar.grammar-test
  "Regression for the grammar demo: the modelled grammar builds and satisfies its
   laws, and a planted ill-formed grammar is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
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
                 (Symbol "root" (produces IDENT))
                 (Symbol "orphan")                       ; in the grammar, but unreachable
                 (Grammar "g"
                   (start root)
                   (symbol root) (symbol IDENT) (symbol orphan))))]
      (is (some #(= "every symbol is reachable from the start symbol" (:law %))
                (s/check db))))))
