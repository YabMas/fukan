(ns lib.arch
  "OPT-IN clean-architecture quality vocab — design-*quality* laws over `lib.code` Modules (strength-2:
   opinions about a clean dependency structure, beyond mere consistency). Required to activate, exactly
   like `lib.code`; it contributes laws only when a model opts in by requiring it. This is NOT a
   methodology/middle layer — it is primitive, reusable architecture-quality structures, grown on real
   need (the first: no import cycle between a pair of modules)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; the law reasons over lib.code Modules/Operations; require it so they are registered.
            [lib.code]))

(defstructure ModuleArchitecture
  "A law-holder for clean-architecture quality constraints over `lib.code` Modules — it has no
   instances of its own (like `fukan.target.correspondence/Realization`); it exists to carry the
   cross-module assertions in their own opt-in concern.

   First law — NO MUTUAL DEPENDENCY: no two Modules may depend on each other (M depends on N and N on
   M), where `module-depends` is the complete graph (calls ∪ data-adoption). `:scope :global` — the
   offenders are the Modules in the cycle, not `ModuleArchitecture`s; naturally vacuous when no Modules
   are modelled. (Full TRANSITIVE acyclicity needs a self-recursive `reaches`-over-`module-depends`
   rule, which the kernel's `check-law-recursion!` rejects — deferred to the role-direction quality
   pass; this 2-cycle law is the cleanly-expressible first cut.)

   The `:rules` below INLINE `lib.code/module-depends-rules` (a law's `:rules` is macro-time literal
   data and cannot reference the var) — keep the two copies in sync."
  (law "no two modules mutually depend"
    :scope :global
    :offenders '[?m]
    :rules '[[(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :owns]    [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :child]   [?r :rel/to ?x]]
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
              (module-owns ?n ?k) [(not= ?m ?n)]]]
    :where '[(module-depends ?m ?n) (module-depends ?n ?m)])

  ;; CONFORMANCE — every cross-subsystem module dependency must follow a declared :may-depend edge.
  ;; Inlines lib.code/module-depends-rules (sync point) + in-subsystem / declared-dep. No rule is
  ;; self-recursive → passes check-law-recursion!. Offender = the module whose dep crosses an
  ;; undeclared subsystem boundary. Vacuous when no Subsystems / no cross-subsystem deps exist.
  (law "every cross-subsystem module dependency follows a declared :may-depend edge"
    :scope :global
    :offenders '[?m]
    :rules '[[(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :owns]    [?r :rel/to ?x]]
             [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :child]   [?r :rel/to ?x]]
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
             [(in-subsystem ?mod ?sub) [?sub :structure/of :lib.code/Subsystem] [?cr :rel/from ?sub] [?cr :rel/kind :child] [?cr :rel/to ?mod]]
             [(declared-dep ?s ?t)     [?s :structure/of :lib.code/Subsystem]   [?mr :rel/from ?s]   [?mr :rel/kind :may-depend] [?mr :rel/to ?t]]]
    :where '[(module-depends ?m ?n)
             (in-subsystem ?m ?s) (in-subsystem ?n ?t) [(not= ?s ?t)]
             (not (declared-dep ?s ?t))])

  ;; DAG ACYCLICITY — the :may-depend edges are direct subsystem→subsystem relations, so sub-reaches
  ;; is PURELY self-recursive (follows :may-depend, calls only itself) — which passes check-law-recursion!.
  (law "the :may-depend graph is acyclic — no subsystem transitively depends on itself"
    :scope :global
    :offenders '[?s]
    :rules '[[(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?t]]
             [(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?mid] (sub-reaches ?mid ?t)]]
    :where '[[?s :structure/of :lib.code/Subsystem] (sub-reaches ?s ?s)])

  ;; MEMBERSHIP TOTALITY — every Module belongs to a Subsystem, so conformance has full coverage.
  ;; Guarded by [?_s :structure/of :lib.code/Subsystem] (a direct datom) → vacuous for subsystem-free
  ;; models (plain lib.code or lib.arch w/o subsystems). Negation routes through the `in-subsystem`
  ;; rule so the zero-member case dodges datascript's empty-relation not-join gotcha.
  (law "every Module belongs to a Subsystem"
    :scope :global
    :offenders '[?mod]
    :rules '[[(in-subsystem ?mod ?sub) [?sub :structure/of :lib.code/Subsystem] [?cr :rel/from ?sub] [?cr :rel/kind :child] [?cr :rel/to ?mod]]]
    :where '[[?_s :structure/of :lib.code/Subsystem]
             [?mod :structure/of :lib.code/Module]
             (not-join [?mod] (in-subsystem ?mod ?_sub))]))
