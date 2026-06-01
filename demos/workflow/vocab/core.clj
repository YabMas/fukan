(ns demos.workflow.vocab.core
  "A control-flow workflow vocabulary, built directly on defstructure. A workflow
   is a directed graph of Steps connected by :next transitions; a step with ≥2
   :next successors is a FORK (fan-out) and a step targeted by ≥2 :next edges is a
   JOIN (fan-in). Well-formedness — reachability, a single entry, termination — is
   expressed as slot laws + recursive free laws over the transition graph.

   Modelling choices worth noting — each is a finding about the core:

   - HETEROGENEOUS STEP KINDS vs the union/scalar gaps. A real workflow language
     distinguishes step *kinds* — start, task, gateway, end — each with its own
     rules (a start has no incoming edge; an end has no outgoing; a gateway has
     ≥2 of one). Expressing those as distinct structures is blocked by the
     single-target-slot gap (#3): :next would need to target a *union* of step
     kinds, which the core can't say, so all kinds collapse into one `Step`. And
     within one `Step`, tagging \"this instance is a gateway\" needs a scalar/enum
     marker — the same gap the ER demo surfaced. So intrinsic step typing sits in
     the blind spot of BOTH gaps at once.

   - …BUT fork and join need NEITHER gap, because they are EMERGENT from graph
     position, not intrinsic kinds: a fork is *any* step with ≥2 :next, a join is
     *any* step that ≥2 :next edges target. No union, no scalar — they fall out of
     the cardinality of the relation. The new finding this domain gives: the
     union/scalar gaps bite for *intrinsic* node typing but not for *positional*
     typing, and a surprising amount of workflow structure is positional.

   - UNORDERED :next IS CORRECT HERE. A fork's branches run in parallel — they are
     an unordered set, not a sequence. So the unordered-slot default (gap #2 in the
     grammar/AST demo, where order was load-bearing) is exactly RIGHT for this
     domain. A positive finding: the default fits whenever concurrency, not
     sequence, is the intent.

   - Cycles (retry loops, rework edges) are authorable: within-module resolves
     references in a second pass, so a step may :next a step declared later, or
     back to an earlier one. The reachability law's `flows` recursion is INLINED
     (a recursive rule may not call a helper rule — datascript diverges on cyclic
     data otherwise), so it terminates over a looping workflow."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Step
  "A step in a workflow. :next are its successor steps; ≥2 successors is a fork
   (fan-out), and a step that ≥2 :next edges target is a join (fan-in). A step
   with no :next is a terminal. Step kinds are emergent from graph position, not
   declared — see the namespace docstring."
  (slot :next (many Step)))

(defstructure Workflow
  "A workflow: a single start step over the set of steps reachable through :next."
  (slot :start (one  Step))
  (slot :step  (some Step))

  ;; Reachability: no unreachable steps — every step is reachable from the start
  ;; through :next. `flows` is transitive reachability over the DIRECT relation
  ;; Step →:next→ Step, with the step INLINED into the recursive rule.
  (law "every step is reachable from the start step"
    :rules '[[(flows ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :next] [?r :rel/to ?b]]
             [(flows ?a ?b)
              [?r :rel/from ?a] [?r :rel/kind :next] [?r :rel/to ?m]
              (flows ?m ?b)]]
    :scope :Step
    :offenders '[?s]
    :where '[[?rg :rel/from ?w] [?rg :rel/kind :step]  [?rg :rel/to ?s]
             [?ri :rel/from ?w] [?ri :rel/kind :start] [?ri :rel/to ?start]
             [(not= ?s ?start)]
             (not (flows ?start ?s))])

  ;; Single entry: the start step has no incoming transition. An edge back to the
  ;; start would re-enter the whole workflow — almost always a modelling error
  ;; (a loop should re-enter a step *after* the start, not the entry itself).
  (law "the start step has no incoming transition"
    :scope :Step
    :offenders '[?start]
    :where '[[?ri :rel/from ?w] [?ri :rel/kind :start] [?ri :rel/to ?start]
             [?rn :rel/kind :next] [?rn :rel/to ?start]])

  ;; Termination: at least one step is terminal (has no :next), so the workflow
  ;; can end. Offender is the workflow itself; it fires when every one of its
  ;; steps has an outgoing transition (the graph can only loop forever).
  (law "the workflow has at least one terminal step (a step with no outgoing transition)"
    :scope :Workflow
    :offenders '[?w]
    :where '[[?rs0 :rel/from ?w] [?rs0 :rel/kind :step]
             (not-join [?w]
               [?rs :rel/from ?w] [?rs :rel/kind :step] [?rs :rel/to ?s]
               (not-join [?s]
                 [?rn :rel/from ?s] [?rn :rel/kind :next]))]))
