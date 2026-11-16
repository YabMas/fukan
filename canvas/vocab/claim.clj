(ns canvas.vocab.claim
  "fukan's own grammar for its self-model: a CLAIM made about the declared design.

   A claim is what a design commits an area to, stated so that it can be found false. It is
   declared here, in the model, whatever checks it: a claim only a review can judge is still a
   node on the graph with its subjects as edges, and one that can be written as a law moves into
   a law without leaving the model. What checks it is its evidence, recorded and not inferred.

   The self-model's and not `fukan.common`'s: no shipped tier names it, and a consumer that
   wants claims declares its own sort of that name."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]))

(defstructure Claim
  "One statement about the declared design, about the elements it names. Its instance name is
   its id — the id a design record carries for it — and its docstring is its statement.
   `:evidence` says what checks it: a round's judgement, named tests, or a named law.

   A name is unique across the whole declaration, not only within its namespace as the grammar
   holds for every other sort, because a record carries a claim by that name alone."
  {:about    [:+ Module Operation Kind]
   :evidence [:enum "round" "test" "law"]}
  (law "one Claim per name — a claim id names exactly one Claim anywhere in the declaration"
    {:offenders [?claim]
     :where [(named ?claim ?id)
             (is ?other :canvas.vocab.claim/Claim)
             (named ?other ?id)
             [(not= ?claim ?other)]]}))
