(ns canvas.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module,
   plus the clean-architecture QUALITY layer over the module/subsystem graph: `ModuleArchitecture`
   (no-mutual-dependency + `:may-depend` conformance / acyclicity / membership) and the
   `latent-boundaries` interface-segregation reading. Strength-2 design opinions, beyond consistency."
  (:require [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]
            [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocab.code.module :refer [Module]]))

(defstructure Subsystem
  "A cluster of Modules realizing a capability — the rung above Module in the grouping ladder
   (Grouping ⊂ Module ⊂ Subsystem). Owns its Modules (`:child`, ownership-on-owner) and DECLARES the
   subsystems it is allowed to depend on (`:may-depend` — the intended architecture DAG, as declared
   intent). `:may-depend` is a self-reference, exactly like `Operation :delegates` — the assembler
   resolves the var-refs."
  {:child      [:* Module]        ; the Modules this subsystem clusters
   :may-depend [:* Subsystem]})   ; the subsystems it is allowed to depend on (declared intent)

;; ── clean-architecture quality laws over the module/subsystem graph ───────────

(defstructure ModuleArchitecture
  "A law-holder for clean-architecture quality constraints over the module/subsystem graph — it has no
   instances of its own (like the correspondence law-holders); it exists to carry the cross-module
   assertions.

   ACYCLIC MODULE DEPENDENCY: no Module may transitively depend on itself — the module-dependency graph
   (`module-depends`, the complete graph: calls ∪ data-adoption) has no cycle. `module-reaches` is its
   transitive closure (a rule-calls-rule recursion, which the kernel now allows after the
   `check-law-recursion!` guard was retired); a Module that `module-reaches` itself sits on a cycle (a
   non-trivial SCC). `:scope :global` — the offenders are the Modules on a cycle; naturally vacuous when
   no Modules are modelled. (Supersedes the earlier 2-cycle-only `M⇄N` check.)

   The `:rules` below INLINE `module/module-depends-rules` (a law's `:rules` is macro-time literal data
   and cannot reference the var) — keep the two copies in sync."
  (law "the module-dependency graph is acyclic — no module transitively depends on itself"
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
             ;; transitive closure of module-depends (rule-calls-rule recursion)
             [(module-reaches ?m ?n) (module-depends ?m ?n)]
             [(module-reaches ?m ?n) (module-depends ?m ?mid) (module-reaches ?mid ?n)]]
    :where '[[?m :structure/of :canvas.vocab.code.module/Module] (module-reaches ?m ?m)])

  ;; CONFORMANCE — every cross-subsystem module dependency must follow a declared :may-depend edge.
  ;; Inlines module/module-depends-rules (sync point) + in-subsystem / declared-dep. Offender = the
  ;; module whose dep crosses an undeclared subsystem boundary. Vacuous when no Subsystems / no
  ;; cross-subsystem deps exist.
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

  ;; DAG ACYCLICITY — the :may-depend edges are direct subsystem→subsystem relations, so sub-reaches
  ;; is PURELY self-recursive (follows :may-depend, calls only itself).
  (law "the :may-depend graph is acyclic — no subsystem transitively depends on itself"
    :scope :global
    :offenders '[?s]
    :rules '[[(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?t]]
             [(sub-reaches ?s ?t) [?r :rel/from ?s] [?r :rel/kind :may-depend] [?r :rel/to ?mid] (sub-reaches ?mid ?t)]]
    :where '[[?s :structure/of :canvas.vocab.code.subsystem/Subsystem] (sub-reaches ?s ?s)])

  ;; MEMBERSHIP TOTALITY — every AUTHORED Module belongs to a Subsystem, so conformance has full
  ;; coverage. Guarded by [?_s :structure/of :canvas.vocab.code.subsystem/Subsystem] (a direct datom) →
  ;; vacuous for subsystem-free models. Negation routes through the `in-subsystem` rule so the
  ;; zero-member case dodges datascript's empty-relation not-join gotcha. Extracted code-fact modules
  ;; (`:val/extracted true`) are out of scope for design-membership — (not [?mod :val/extracted true]).
  (law "every Module belongs to a Subsystem"
    :scope :global
    :offenders '[?mod]
    :rules '[[(in-subsystem ?mod ?sub) [?sub :structure/of :canvas.vocab.code.subsystem/Subsystem] [?cr :rel/from ?sub] [?cr :rel/kind :child] [?cr :rel/to ?mod]]]
    :where '[[?_s :structure/of :canvas.vocab.code.subsystem/Subsystem]
             [?mod :structure/of :canvas.vocab.code.module/Module]
             (not [?mod :val/extracted true])
             (not-join [?mod] (in-subsystem ?mod ?_sub))]))

;; ── latent-boundary discovery (interface segregation, bottom-up) ──────────────

(defn latent-boundaries
  "Bottom-up boundary DISCOVERY (Parnas's decomposition criterion / Interface Segregation, made
   mechanical): code Modules whose PUBLIC surface has split into ≥2 consumer-DISJOINT clienteles — a
   latent sub-interface that has crystallized with its own external clientele but that no formal
   contract names. For each such Module, the discovered sub-interface(s): a bundle of ≥2 public ops
   sharing a clientele, disjoint from the rest of the Module's public surface.

   A SIGNAL for human judgment, NOT a violation (like `module/module-dependencies` /
   `module/uncovered-calls`): it detects that a seam has crystallized; whether it DESERVES a formal
   split is the human's call (detect-vs-decide). COUNT-INVARIANT by construction: a bundle's clientele
   may grow and the bundle stays disjoint from the rest — so the seam stays visible, unlike a
   single-consumer test which goes silent exactly as a shared internal surface accretes more consumers.

   ON-GRAPH, COMPOSITIONAL — the `cozo.rules/surface` building blocks (`public_op` / `clientele` /
   `co_consumed` / `consumed`, over the EXTRACTED `:calls` graph) feed Cozo's `ConnectedComponents`
   fixed rule: a public op's clientele is the OTHER code modules that call it; two ops are co-consumed
   when their clienteles overlap; the connected components of the co-consumed graph are the candidate
   sub-interfaces. A component is reported when it COHERES (≥2 ops) and is a PROPER subset of its
   module's externally-consumed surface — both COUNT aggregations OVER the components, the very reading
   datascript could not express (connected-component count was one of the cases that justified the Cozo
   engine). Only the final bundle assembly is Clojure. Returns a sorted map
   `{module-name [{:ops [name…] :clientele [module-name…]} …]}`; empty ⇔ no module's public surface
   has split into disjoint clienteles.

   GUARD: no co-consumption anywhere ⟹ no latent boundary (a lone captive is below the cohesion gate).
   That domain fact is also load-bearing mechanically — `ConnectedComponents` panics on a wholly-empty
   edge relation — so we short-circuit before calling it."
  [db]
  (let [base (str rules/eav rules/surface)]
    (if (empty? (db/q db (str base "?[a, b] := co_consumed[a, b]")))
      (sorted-map)
      (->> (db/q db (str base "
comp[node, cid] <~ ConnectedComponents(co_consumed[a, b])
csize[mod, cid, count(node)] := comp[node, cid], in_module[node, mod]
total[mod, count(o)]         := consumed[o, mod]
flagged[mod, cid] := csize[mod, cid, sz], sz >= 2, total[mod, t], sz < t
?[mod, cid, opname, clmod] := flagged[mod, cid], comp[node, cid], in_module[node, mod],
                             ename[node, opname], clientele[node, clmod]
"))
           (group-by (fn [[mod cid _ _]] [mod cid]))
           (reduce (fn [acc [[mod _cid] grp]]
                     (let [bundle {:ops       (sort (distinct (map #(nth % 2) grp)))
                                   :clientele (sort (distinct (map #(nth % 3) grp)))}]
                       (update acc mod (fnil conj []) bundle)))
                   {})
           (reduce-kv (fn [acc mod bs] (assoc acc mod (vec (sort-by (comp count :ops) > bs))))
                      (sorted-map))))))
