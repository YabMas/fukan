(ns demos.grammar.model.key-value
  "A tiny acyclic grammar modelled with the grammar vocabulary:

     pair  → key ':' value
     key   → IDENT
     value → STRING | NUMBER

   Terminals (COLON, IDENT, STRING, NUMBER) are declared first so the forward
   references in the nonterminal productions resolve under the core's
   forward-only name resolution."
  (:require [fukan.canvas.core.structure :as s]
            [demos.grammar.vocab.core :refer [Symbol Grammar]]))

(defn build []
  (s/with-structures
    (s/within-module "key-value"
      ;; terminals — no productions
      (Symbol "COLON")
      (Symbol "IDENT")
      (Symbol "STRING")
      (Symbol "NUMBER")
      ;; nonterminals
      (Symbol "key"   (produces IDENT))
      (Symbol "value" (produces STRING NUMBER))
      (Symbol "pair"  (produces key COLON value))
      (Grammar "kv"
        (start  pair)
        (symbol pair) (symbol key) (symbol value)
        (symbol COLON) (symbol IDENT) (symbol STRING) (symbol NUMBER)))))
