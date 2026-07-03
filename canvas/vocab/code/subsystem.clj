(ns canvas.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module.
   The `:may-depend` slot carries its own TEETH (conformance + DAG acyclicity — a declared
   architecture that isn't enforced is prose); the ADOPTED layering demands (module-graph
   acyclicity, membership totality) and the `latent-boundaries` reading live in
   `canvas.principles.layered-architecture`."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocab.code.module :refer [Module]]))

(defstructure Subsystem
  "A cluster of Modules realizing a capability — the rung above Module in the grouping ladder
   (Grouping ⊂ Module ⊂ Subsystem). Owns its Modules (`:child`, ownership-on-owner) and DECLARES the
   subsystems it is allowed to depend on (`:may-depend` — the intended architecture DAG, as declared
   intent). `:may-depend` is a self-reference, exactly like `Operation :delegates` — the assembler
   resolves the var-refs.

   The laws are the SLOT SEMANTICS of `:may-depend` — what declaring the DAG MEANS; without them
   the slot is prose. CONFORMANCE: every cross-subsystem module dependency (`module-depends`, the
   complete graph: calls ∪ data-adoption) follows a declared `:may-depend` edge — the offender is
   the crossing Module, hence `:scope :global`; the `:rules` INLINE a copy of
   `canvas.vocab.code.module/module-depends-rules` (a law's `:rules` is macro-time literal data —
   it cannot reference the var; keep the copies in sync). DAG ACYCLICITY (self-scoped): no
   Subsystem `sub-reaches` itself over `:may-depend` — a cyclic declaration is incoherent intent.
   Both are naturally vacuous when no Subsystems / no cross-subsystem deps are modelled."
  {:child      [:* {:contains true} Module]   ; the Modules this subsystem clusters
   :may-depend [:* Subsystem]}   ; the subsystems it is allowed to depend on (declared intent)
  (law "every cross-subsystem module dependency follows a declared :may-depend edge"
    :scope :global
    :offenders '[?m]
    :rules '[[(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :owns]    [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :child]   [?r :rel/to ?x]]
             [(module-depends ?m ?n)
              (module-owns ?m ?op1) [?dr :rel/from ?op1] [?dr :rel/kind :delegates] [?dr :rel/to ?op2]
              (module-owns ?n ?op2) [(not= ?m ?n)]]
             [(module-depends ?m ?n)
              (module-owns ?m ?op)
              (or-join [?op ?sch]
                (and [?ir :rel/from ?op] [?ir :rel/kind :in]  [?ir :rel/to ?sch])
                (and [?o2 :rel/from ?op] [?o2 :rel/kind :out] [?o2 :rel/to ?sch]))
              [?sch :val/kind "ref"]
              [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
              (module-owns ?n ?k) [(not= ?m ?n)]]
             [(in-subsystem ?mod ?sub) [?sub :structure/of :canvas.vocab.code.subsystem/Subsystem] [?cr :rel/from ?sub] [?cr :rel/kind :child] [?cr :rel/to ?mod]]
             [(declared-dep ?s ?t)     [?s :structure/of :canvas.vocab.code.subsystem/Subsystem]   [?mr :rel/from ?s]   [?mr :rel/kind :may-depend] [?mr :rel/to ?t]]]
    :where '[(module-depends ?m ?n)
             (in-subsystem ?m ?s) (in-subsystem ?n ?t) [(not= ?s ?t)]
             (not (declared-dep ?s ?t))])
  ;; self-scoped: the offender IS a Subsystem, so the scope clause [?s :structure/of ::Subsystem]
  ;; is injected — no :scope :global, no explicit tag clause. sub-reaches follows :may-depend
  ;; edges directly and is PURELY self-recursive.
  (law "the :may-depend graph is acyclic — no subsystem transitively depends on itself"
    :offenders '[?s]
    :rules '[[(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?t]]
             [(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?mid] (sub-reaches ?mid ?t)]]
    :where '[(sub-reaches ?s ?s)]))
