(ns canvas.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module.
   The `:may-depend` slot carries its own TEETH (conformance + DAG acyclicity — a declared
   architecture that isn't enforced is prose); the ADOPTED layering demands (module-graph
   acyclicity, membership totality) and the `latent-boundaries` reading live in
   `canvas.principles.layered-architecture`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [canvas.vocab.code.module :refer [Module]]))

;; in-subsystem — Subsystem membership as a DEFRELATION (the `contains` union restricted to a
;; Subsystem container), so the conformance law here and ModuleArchitecture's membership-totality
;; law (in canvas.principles.layered-architecture) read it by name instead of each inlining a copy.
(s/defrelation :in-subsystem
  "Module ?mod is clustered by Subsystem ?sub — the `contains` union restricted to a Subsystem container."
  '[?mod ?sub]
  '[[?sub :structure/of :canvas.vocab.code.subsystem/Subsystem] (contains ?sub ?mod)])

(defstructure Subsystem
  "A cluster of Modules realizing a capability — the rung above Module in the grouping ladder
   (Grouping ⊂ Module ⊂ Subsystem). Owns its Modules (`:child`, ownership-on-owner) and DECLARES the
   subsystems it is allowed to depend on (`:may-depend` — the intended architecture DAG, as declared
   intent). `:may-depend` is a self-reference, exactly like `Operation :delegates` — the assembler
   resolves the var-refs.

   The laws are the SLOT SEMANTICS of `:may-depend` — what declaring the DAG MEANS; without them
   the slot is prose. CONFORMANCE: every cross-subsystem module dependency (`module-depends`, the
   complete graph: calls ∪ data-adoption) follows a declared `:may-depend` edge — the offender is
   the crossing Module, hence `:scope :global`. `module-depends` and `in-subsystem` are injected
   defrelations (read by name, no inlined copy); only `declared-dep` (this slot's own reading) is
   a local `:rules` entry. DAG ACYCLICITY (self-scoped): no Subsystem `sub-reaches` itself over
   `:may-depend` — a cyclic declaration is incoherent intent. Both are naturally vacuous when no
   Subsystems / no cross-subsystem deps are modelled."
  {:child      [:* {:contains true} Module]   ; the Modules this subsystem clusters
   :may-depend [:* Subsystem]}   ; the subsystems it is allowed to depend on (declared intent)
  (law "every cross-subsystem module dependency follows a declared :may-depend edge"
    :scope :global
    :offenders '[?m]
    :rules '[[(declared-dep ?s ?t) [?s :structure/of :canvas.vocab.code.subsystem/Subsystem] [?mr :rel/from ?s] [?mr :rel/kind :may-depend] [?mr :rel/to ?t]]]
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
