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
    :where '[(module-depends ?m ?n) (module-depends ?n ?m)]))
