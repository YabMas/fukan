(ns demos.grammar.model.key-value
  "A tiny acyclic grammar modelled with the grammar vocabulary:

     pair  → key ':' value
     key   → IDENT
     value → STRING | NUMBER

   `value` has TWO Productions (alternation); `pair` has ONE Production whose rhs
   is the ORDERED sequence [key COLON value]. Terminals (COLON, IDENT, STRING,
   NUMBER) are declared first so the forward references in the productions resolve."
  (:require [fukan.canvas.core.structure :as s]
            ;; Production is authored inline (a value) → not referred
            [demos.grammar.vocab.core :refer [Symbol Grammar]]))

(defn build []
  (s/with-structures
    (s/within-module "key-value"
      ;; terminals — no productions
      (Symbol "COLON")
      (Symbol "IDENT")
      (Symbol "STRING")
      (Symbol "NUMBER")
      ;; nonterminals — :produces one or more Production alternatives;
      ;; a Production's :rhs is an ordered sequence (vector)
      (Symbol "key"   (produces (Production (rhs [IDENT]))))
      (Symbol "value" (produces (Production (rhs [STRING]))      ; value → STRING
                                (Production (rhs [NUMBER]))))    ; value → NUMBER
      (Symbol "pair"  (produces (Production (rhs [key COLON value]))))  ; ordered RHS
      (Grammar "kv"
        (start  pair)
        (symbol pair) (symbol key) (symbol value)
        (symbol COLON) (symbol IDENT) (symbol STRING) (symbol NUMBER)))))
