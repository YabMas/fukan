(ns fukan.common.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module.
   The `:may-depend` slot carries its own TEETH (conformance + DAG acyclicity — a declared
   architecture that isn't enforced is prose). Two module-graph demands — module-dependency-graph
   acyclicity and membership totality (every Module belongs to a Subsystem) — also ride here: they
   concern the Module graph + clustering, which Subsystem is the natural holder for. (They were
   rehomed from the retired `canvas.principles.layered-architecture` when the principles layer was
   cut to focus scope on vocab + verification.) The module-graph RELATIONS those laws read —
   `module-owns`/`module-depends` (the derived dependency graph) + the `module-dependencies` reader —
   live here too: their only consumers are these architecture laws + readers (a cross-module analysis,
   not part of what a single Module IS), so they moved off `module` to sit with what uses them."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [fukan.common.vocab.code.module :refer [Module]]))

;; ── the module-graph relations the architecture laws read ─────────────────────
;; All three defrelations are injected into every law/query by `check`/`vocab-rules`, so the laws that
;; need them reference them BY NAME instead of each re-inlining a copy (the compiler emits only the
;; rules a query reaches). `in-subsystem`/`module-owns` are `contains` restricted to a container kind;
;; `module-depends` is the module→module dependency graph the `:may-depend` laws are checked against.
;; They live here (not on the elements) because their only consumers are Subsystem's architecture laws
;; + readers — a cross-module analysis, which Subsystem is the natural holder for.

;; in-subsystem — Subsystem membership: the `contains` union restricted to a Subsystem container.
(s/defrelation :in-subsystem
  "Module ?mod is clustered by Subsystem ?sub — the `contains` union restricted to a Subsystem container."
  '[?mod ?sub]
  '[[?sub :structure/of :fukan.common.vocab.code.subsystem/Subsystem] (contains ?sub ?mod)])

;; module-owns — Module ownership: the `contains` genus
;; restricted to a Module container. A helper for `module-depends`.
(s/defrelation :module-owns
  "Module ?m owns ?x — the `contains` genus restricted to a Module container."
  '[?m ?x]
  '[[?m :structure/of :fukan.common.vocab.code.module/Module] (contains ?m ?x)])

(s/defrelation :module-depends
  "the COMPLETE module→module dependency graph: a call dependency (?m owns an op that :delegates to
   an op ?n owns) UNIONed with data-adoption (?m owns an op whose :in/:out ref-Schema references a Kind
   ?n owns, by name). The reader `module-dependencies` and the layering laws below read this by name."
  '[?m ?n]
  '[(module-owns ?m ?op)
    (or-join [?op ?n]
      (and (delegates ?op ?op2) (module-owns ?n ?op2))
      (and (in ?op ?sch)  (names-kind ?sch ?k) (module-owns ?n ?k))
      (and (out ?op ?sch) (names-kind ?sch ?k) (module-owns ?n ?k)))
    [(not= ?m ?n)]])

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
  {:child      [:* Module]      ; the Modules this subsystem clusters (`:child` is a `contains` species — see vocab/grouping)
   :may-depend [:* Subsystem]}  ; the subsystems it is allowed to depend on (declared intent)
  (law "every cross-subsystem module dependency follows a declared :may-depend edge"
    :scope :global
    :offenders '[?m]
    :rules '[[(declared-dep ?s ?t) [?s :structure/of :fukan.common.vocab.code.subsystem/Subsystem] (may-depend ?s ?t)]]
    :where '[(module-depends ?m ?n)
             (in-subsystem ?m ?s) (in-subsystem ?n ?t) [(not= ?s ?t)]
             (not (declared-dep ?s ?t))])
  ;; self-scoped: the offender IS a Subsystem, so the scope clause [?s :structure/of ::Subsystem]
  ;; is injected — no :scope :global, no explicit tag clause. sub-reaches follows :may-depend
  ;; edges directly and is PURELY self-recursive.
  (law "the :may-depend graph is acyclic — no subsystem transitively depends on itself"
    :offenders '[?s]
    :rules '[[(sub-reaches ?s ?t) (may-depend ?s ?t)]
             [(sub-reaches ?s ?t) (may-depend ?s ?mid) (sub-reaches ?mid ?t)]]
    :where '[(sub-reaches ?s ?s)])
  ;; ── the module-graph demands, rehomed from the retired layered-architecture principle ──
  ;; ACYCLIC MODULE DEPENDENCY: no Module transitively depends on itself — the module-dependency graph
  ;; (`module-depends`, the complete graph: calls ∪ data-adoption) has no cycle. `module-reaches` is its
  ;; transitive closure (a rule-calls-rule recursion the kernel allows); a Module that reaches itself sits
  ;; on a cycle. `:scope :global` — the offenders are the Modules on a cycle, not Subsystems; naturally
  ;; vacuous when no Modules are modelled.
  (law "the module-dependency graph is acyclic — no module transitively depends on itself"
    :scope :global
    :offenders '[?m]
    :rules '[[(module-reaches ?m ?n) (module-depends ?m ?n)]
             [(module-reaches ?m ?n) (module-depends ?m ?mid) (module-reaches ?mid ?n)]]
    :where '[[?m :structure/of :fukan.common.vocab.code.module/Module] (module-reaches ?m ?m)])
  ;; MEMBERSHIP TOTALITY — every AUTHORED Module belongs to a Subsystem, so conformance has full
  ;; coverage. Guarded by a Subsystem existing (→ vacuous for subsystem-free models); negation routes
  ;; through the injected `in-subsystem` defrelation. Extracted code-fact modules are out of scope.
  (law "every Module belongs to a Subsystem"
    :scope :global
    :offenders '[?mod]
    :where '[[?_s :structure/of :fukan.common.vocab.code.subsystem/Subsystem]
             [?mod :structure/of :fukan.common.vocab.code.module/Module]
             (not (fact ?mod))
             (not-join [?mod] (in-subsystem ?mod ?_sub))]))

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set of
   [caller-name callee-name] pairs. A pure read over the reified code graph."
  [db]
  (set (cq/q '[:find ?mn ?nn :in $
               :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]
             db)))

(defn unrealized-dependencies
  "The declared `:may-depend` edges the code does NOT realize — a Subsystem ?s that declares it may
   depend on ?t, but NO Module in ?s actually depends (`module-depends`: calls ∪ data-adoption) on a
   Module in ?t. As a set of [from-subsystem to-subsystem] name pairs.

   The DUAL of the conformance law: conformance catches CODE that outruns the declared DAG
   (an undeclared cross-subsystem dependency — a violation); this catches a declared DAG that outruns
   the CODE (a declared edge with no realizing dependency — over-declaration: intentional headroom or
   stale intent). A SIGNAL, not a violation — headroom is a
   legitimate choice, so an unrealized declared dependency reports rather than fails a check."
  [db-arg]
  (set (cq/q '[:find ?sn ?tn :in $ %
               :where [?s :structure/of :fukan.common.vocab.code.subsystem/Subsystem] (may-depend ?s ?t)
                      [?s :entity/name ?sn] [?t :entity/name ?tn]
                      (not-join [?s ?t]
                        (module-depends ?m ?n) (in-subsystem ?m ?s) (in-subsystem ?n ?t))]
             db-arg (s/vocab-rules))))
