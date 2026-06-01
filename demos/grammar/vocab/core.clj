(ns demos.grammar.vocab.core
  "A vocabulary for context-free grammars, built directly on defstructure. A
   grammar is a graph of Symbols: a nonterminal :produces the symbols its
   productions reference; a terminal produces none. Well-formedness is a law.

   Modelling choices worth noting — each is a finding about the core:
   - Terminal vs nonterminal is NOT two structures (that would need a union /
     refinement for the :produces target). It's one `Symbol`; a terminal is a
     Symbol with no :produces — collapsed the way value/record collapsed into
     Type. The union case is what the deferred refinement mechanism would serve.
   - :produces is an UNORDERED set, so this captures a production's *references*
     but not its *sequence*. Ordered composition (a production is an ordered
     string of symbols; an AST's children are ordered) has no native expression
     on the core yet — the key gap this domain surfaces.
   - Grammars are modelled ACYCLIC here: a cyclic grammar (left-recursion,
     e.g. expr → expr '+' term) can't be authored under the core's forward-only
     name resolution (the back-edge references a symbol not yet emitted). Also a
     surfaced gap: authoring cyclic models."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Symbol
  "A grammar symbol. A nonterminal :produces the symbols its productions
   reference; a terminal :produces none."
  (slot :produces (many Symbol)))

(defstructure Grammar
  "A context-free grammar: a start symbol over a set of symbols."
  (slot :start  (one  Symbol))
  (slot :symbol (some Symbol))
  ;; No useless symbols: every symbol is reachable from the start. The recursive
  ;; `derives` rule INLINES its step (per the core's rule-calls-rule constraint —
  ;; a recursive rule may not call a helper rule).
  (law "every symbol is reachable from the start symbol"
    :rules '[[(derives ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :produces] [?r :rel/to ?b]]
             [(derives ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :produces] [?r :rel/to ?m]
              (derives ?m ?b)]]
    :scope :Symbol
    :offenders '[?s]
    :where '[[?rg :rel/from ?g] [?rg :rel/kind :symbol] [?rg :rel/to ?s]
             [?ri :rel/from ?g] [?ri :rel/kind :start]  [?ri :rel/to ?start]
             [(not= ?s ?start)]
             (not (derives ?start ?s))]))
