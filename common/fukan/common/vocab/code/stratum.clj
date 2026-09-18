(ns fukan.common.vocab.code.stratum
  "Code vocab — `Stratum`: one LEVEL of a codebase in the sense of stratified design (Abelson &
   Sussman, *Lisp: A Language for Stratified Design*): the Modules that provide a vocabulary, and
   the strata that vocabulary is written in.

   The third grouping above a namespace, and each sibling differs from it on one axis:

   - `Band` decides membership by NAMESPACE PREFIX, so it partitions by package; the levels inside
     one package — a schema, the model read off it, the store written in both — share a prefix and
     are invisible to it. A stratum's modules are AUTHORED.
   - `Subsystem` OWNS its Modules (`:child`) as one capability — Parnas's axis, what a boundary
     hides — and once one exists every authored Module must belong to one. A stratum only RELATES
     its Modules, as a level — the vertical axis, what a boundary provides upward — so a Module
     keeps whatever home it has, and one in no stratum is unread rather than wrong.
   - `Subsystem` checks against `module-depends`, built from authored `:delegates`. A stratum, like
     a Band, checks against `ns-depends`, which extraction produces with nothing authored, so an
     unverified call graph cannot make a stratum pass.

   `stratum` names only this sort in the shipped vocabulary. The kernel's provenance tiers
   (`substrate/stratum-attr`) and the stratified evaluation of Datalog are different senses that
   never reach a project's model.

   ⚠ Like `band`, this reads the Clojure extraction's `ns-depends` and the `Module ↦ Ns` pairing by
   NAME, not by require. A project with a different extractor mints neither and gets vacuous laws
   rather than a load error; the extractor-neutral code-unit sort that would retire both
   exceptions waits for a second extractor."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.vocab.code.module :refer [Module]]))

(defstructure Stratum
  "A level of the code: the Modules whose vocabulary it is (`:provided-by`), and the strata that
   vocabulary is written in (`:rests-on`). The docstring of an instance says WHAT it provides — the
   primitives, and how they combine — because that is the claim a level makes, and no count of its
   edges states it.

   The laws are the slot semantics of `:rests-on`. Every edge out of a level is a DECISION: a code
   dependency on a stratum it does not rest on is a violation, so reaching past a level is declared
   or it does not happen. There is no coverage law — a stratum is declared where someone has read
   an area's levels, and a partition forced over every module at once would be the big bang
   declaring strata exists to avoid."
  {:provided-by [:+ Module]   ; the modules whose vocabulary this level is
   :rests-on    [:* Stratum]} ; the levels it is written in (declared intent)

  (law "every code dependency between the modules of two strata follows a declared :rests-on edge"
    ;; The offender is the whole edge plus the two levels it crosses, as Band reports it, so a
    ;; finding names the require that is wrong and not only the namespace holding it. Only DIRECT
    ;; edges count: resting on a stratum that rests on a third does not license a call into the
    ;; third — that is the reach past a level this law exists to surface.
    {:scope :global
     :offenders [?from ?to ?from-stratum ?to-stratum]
     :rules [[(rests ?s ?t) (is ?s ::Stratum) (rests-on ?s ?t)]]
     :where [(ns-depends ?from ?to)
             (corresponds ?from-module ?from) (provided-by ?from-stratum ?from-module)
             (corresponds ?to-module ?to) (provided-by ?to-stratum ?to-module)
             [(not= ?from-stratum ?to-stratum)]
             (not (rests ?from-stratum ?to-stratum))]})

  (law "a Module provides at most one Stratum"
    {:scope :global
     :offenders [?module]
     :where [(provided-by ?s ?module) (provided-by ?t ?module) [(not= ?s ?t)]]})

  (law "the :rests-on graph is acyclic — no stratum is written in itself"
    {:offenders [?stratum]
     :rules [[(stratum-reaches ?s ?t) (rests-on ?s ?t)]
             [(stratum-reaches ?s ?t) (rests-on ?s ?mid) (stratum-reaches ?mid ?t)]]
     :where [(stratum-reaches ?stratum ?stratum)]}))
