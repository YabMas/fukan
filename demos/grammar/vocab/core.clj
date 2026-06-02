(ns demos.grammar.vocab.core
  "A vocabulary for context-free grammars, built directly on defstructure. A
   grammar is a graph of Symbols. A nonterminal :produces one or more Productions
   (its ALTERNATIVES); each Production's :rhs is the ORDERED sequence of symbols on
   that production's right-hand side. A terminal produces nothing. Well-formedness
   (no useless symbols) is a recursive law over Symbol →:produces→ Production →:rhs→
   Symbol.

   Modelling choices worth noting — each is a finding about the core:

   - ALTERNATION vs SEQUENCE are now distinct, the way a grammar means them. An
     earlier version flattened both into a single unordered `:produces (many
     Symbol)`, which conflated `value → STRING | NUMBER` (two alternatives) with
     `pair → key ':' value` (one ordered sequence). Separating them needs BOTH new
     primitives at once: a Symbol :produces many Productions (alternation), and a
     Production's :rhs is `(ordered Symbol)` (sequence, position-bearing).

   - A Production is a `^:value`: a production IS its (ordered) rhs sequence, so two
     productions with the same rhs are one node (value-identity). Authored inline,
     anonymous — there is no \"which\" production beyond its sequence.

   - The reachability law's `derives` recursion INLINES its two-hop step (Symbol →
     Production → Symbol) — a recursive rule may not call a helper rule (datascript
     diverges on cyclic data otherwise) — so it terminates over a cyclic grammar.
     Cyclic/left-recursive grammars are authorable (within-module's second pass
     resolves forward references)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure ^:value Production
  "One production alternative: the ORDERED right-hand side sequence of symbols.
   Value-identified — a production is its rhs."
  (slot :rhs (ordered Symbol)))

(defstructure Symbol
  "A grammar symbol. A nonterminal :produces one or more Productions (its
   alternatives); a terminal produces none."
  (slot :produces (many Production)))

(defstructure Grammar
  "A context-free grammar: a start symbol over a set of symbols."
  (slot :start  (one  Symbol))
  (slot :symbol (some Symbol))
  ;; No useless symbols: every symbol is reachable from the start. `derives` is
  ;; reachability over the indirect graph Symbol →:produces→ Production →:rhs→
  ;; Symbol, with the two-hop step INLINED into the recursive rule.
  (law "every symbol is reachable from the start symbol"
    :rules '[[(derives ?a ?b)
              [?rp :rel/from ?a]    [?rp :rel/kind :produces] [?rp :rel/to ?prod]
              [?rr :rel/from ?prod] [?rr :rel/kind :rhs]      [?rr :rel/to ?b]]
             [(derives ?a ?b)
              [?rp :rel/from ?a]    [?rp :rel/kind :produces] [?rp :rel/to ?prod]
              [?rr :rel/from ?prod] [?rr :rel/kind :rhs]      [?rr :rel/to ?m]
              (derives ?m ?b)]]
    :scope :Symbol
    :offenders '[?s]
    :where '[[?rg :rel/from ?g] [?rg :rel/kind :symbol] [?rg :rel/to ?s]
             [?ri :rel/from ?g] [?ri :rel/kind :start]  [?ri :rel/to ?start]
             [(not= ?s ?start)]
             (not (derives ?start ?s))]))
