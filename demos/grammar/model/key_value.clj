(ns demos.grammar.model.key-value
  "A tiny acyclic grammar modelled with the grammar vocabulary:

     pair  → key ':' value
     key   → IDENT
     value → STRING | NUMBER

   `value` has TWO Productions (alternation); `pair` has ONE Production whose rhs
   is the ORDERED sequence [kv-key COLON kv-value]. Terminals (COLON, IDENT, STRING,
   NUMBER) are declared first so the forward references in the productions resolve.

   Note: `key` and `value` clash with clojure.core, so the instance vars are named
   `kv-key` and `kv-value`; `pair` is named `kv-pair` for consistency. Clause HEADS
   (slot names like `produces`, `start`, `symbol`) are unaffected."
  (:require [fukan.canvas.core.assemble :as a]
            ;; Production is authored inline (a value) — not referred directly
            [demos.grammar.vocab.core :refer [Symbol Grammar Production]]))

;; terminals — no productions
(def COLON  (Symbol "COLON"))
(def IDENT  (Symbol "IDENT"))
(def STRING (Symbol "STRING"))
(def NUMBER (Symbol "NUMBER"))

;; nonterminals — :produces one or more Production alternatives;
;; a Production's :rhs is an ordered sequence (vector)
(def kv-key   (Symbol "key"   (produces (Production (rhs [IDENT])))))
(def kv-value (Symbol "value" (produces (Production (rhs [STRING]))     ; value → STRING
                                         (Production (rhs [NUMBER])))))  ; value → NUMBER
(def kv-pair  (Symbol "pair"  (produces (Production (rhs [kv-key COLON kv-value])))))  ; ordered RHS

(def kv-grammar
  (Grammar "kv"
    (start  kv-pair)
    (symbol kv-pair) (symbol kv-key) (symbol kv-value)
    (symbol COLON) (symbol IDENT) (symbol STRING) (symbol NUMBER)))

(defn build [] (a/assemble ['demos.grammar.model.key-value]))
