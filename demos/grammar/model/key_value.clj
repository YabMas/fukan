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
(Symbol COLON)
(Symbol IDENT)
(Symbol STRING)
(Symbol NUMBER)

;; nonterminals — :produces one or more Production alternatives;
;; a Production's :rhs is a sequence (authoring order is the order)
(Symbol ^{:name "key"}   kv-key   {:produces [(Production {:rhs [IDENT]})]})
(Symbol ^{:name "value"} kv-value {:produces [(Production {:rhs [STRING]})     ; value → STRING
                                              (Production {:rhs [NUMBER]})]})  ; value → NUMBER
(Symbol ^{:name "pair"}  kv-pair  {:produces [(Production {:rhs [kv-key COLON kv-value]})]})  ; ordered RHS

(Grammar ^{:name "kv"} kv-grammar
  {:start  kv-pair
   :symbol [kv-pair kv-key kv-value COLON IDENT STRING NUMBER]})

(defn build [] (a/assemble ['demos.grammar.model.key-value]))
