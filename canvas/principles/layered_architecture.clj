(ns canvas.principles.layered-architecture
  "PRINCIPLE — layered, intentional architecture (Ousterhout APoSD; DDD bounded contexts;
   Parnas information hiding).

   Dependencies are DECLARED and DIRECTED: the module graph is acyclic and fully clustered
   (`ModuleArchitecture`: acyclicity + membership totality — the `:may-depend`
   conformance/acyclicity teeth ride `Subsystem` itself, slot semantics with the slot); the
   code call graph realizes the declared design (the generated `:corresponds/Operation.delegates-realized`
   and `:corresponds/Operation.delegates-faithful` demand laws, derived from Operation's `:delegates`
   slot carrying `{:realized-by :calls :altitude :container :faithful true}`);
   `latent-boundaries` discovers bottom-up boundaries the design hasn't drawn yet (Parnas/ISP
   consumer-disjointness — the information-hiding flavour).

   Realization is at MODULE-DEPENDENCY altitude, not exact op-pair: real dependencies are often
   indirect (dispatch, internal leaves) — the author sketches the module dependency on an exposed op.
   Fidelity is scoped to MODELLED-BOTH-ENDS: a call into an unmodelled namespace is a coverage gap
   (the `uncovered-calls` query), NOT a fidelity violation — we only enforce boundaries we claim to model.

   Generated laws: `:corresponds/Operation.delegates-realized`, `:corresponds/Operation.delegates-faithful`.
   Judgment readers: `uncovered-calls`, `unfaithful-calls`, `unrealized-delegates`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :refer [eav]]
            [canvas.vocab.code.module :as module]))

;; ── clean-architecture quality laws over the module/subsystem graph ───────────

(defstructure ModuleArchitecture
  "A law-holder for the ADOPTED layering demands over the module graph — it has no instances of its
   own (like the correspondence law-holders); it carries the cross-module assertions no single
   element's declaration gives meaning to. (The `:may-depend` slot's own teeth — conformance + DAG
   acyclicity — ride `Subsystem` itself: slot semantics live with the slot.)

   ACYCLIC MODULE DEPENDENCY: no Module may transitively depend on itself — the module-dependency graph
   (`module-depends`, the complete graph: calls ∪ data-adoption) has no cycle. `module-reaches` is its
   transitive closure (a rule-calls-rule recursion, which the kernel now allows after the
   `check-law-recursion!` guard was retired); a Module that `module-reaches` itself sits on a cycle (a
   non-trivial SCC). `:scope :global` — the offenders are the Modules on a cycle; naturally vacuous when
   no Modules are modelled. (Supersedes the earlier 2-cycle-only `M⇄N` check.)

   `module-depends` and `in-subsystem` are injected defrelations (from `code/module` and
   `code/subsystem`) read by name; only `module-reaches` (this law's own transitive closure over
   `module-depends`, a rule-calls-rule recursion the kernel allows) is a local `:rules` entry."
  (law "the module-dependency graph is acyclic — no module transitively depends on itself"
    :scope :global
    :offenders '[?m]
    :rules '[[(module-reaches ?m ?n) (module-depends ?m ?n)]
             [(module-reaches ?m ?n) (module-depends ?m ?mid) (module-reaches ?mid ?n)]]
    :where '[[?m :structure/of :canvas.vocab.code.module/Module] (module-reaches ?m ?m)])

  ;; MEMBERSHIP TOTALITY — every AUTHORED Module belongs to a Subsystem, so conformance has full
  ;; coverage. Guarded by [?_s :structure/of :canvas.vocab.code.subsystem/Subsystem] (a direct datom) →
  ;; vacuous for subsystem-free models. Negation routes through the injected `in-subsystem` defrelation
  ;; so the zero-member case dodges datascript's empty-relation not-join gotcha. Extracted code-fact
  ;; modules (`:val/extracted true`) are out of scope for design-membership — (not [?mod :val/extracted true]).
  (law "every Module belongs to a Subsystem"
    :scope :global
    :offenders '[?mod]
    :where '[[?_s :structure/of :canvas.vocab.code.subsystem/Subsystem]
             [?mod :structure/of :canvas.vocab.code.module/Module]
             (not [?mod :val/extracted true])
             (not-join [?mod] (in-subsystem ?mod ?_sub))]))

;; ── latent-boundary discovery (interface segregation, bottom-up) ──────────────

(def ^:private surface
  "Code-surface descriptive CozoScript primitives, built on `eav` — the reusable building blocks
   `latent-boundaries` composes with Cozo's `ConnectedComponents`. `public_op`: a non-private extracted
   Operation (the externally-callable surface). `clientele`: the OTHER modules that call a public op.
   `co_consumed`: two public ops in the same module captured by a shared clientele. `consumed`: a public
   op with any external clientele, plus its module. NB names code-vocab (`Operation`/`:calls`) — which is
   why it lives here (canvas — outside the generic cozo substrate)."
  "
public_op[o] := structof[o, 'canvas.vocab.code.operation/Operation'], extracted[o], not isprivate[o]
clientele[o, cm] := public_op[o], relkind[c, 'calls'], relto[c, o], relfrom[c, caller],
                    in_module[caller, cm], in_module[o, om], cm != om
co_consumed[a, b] := clientele[a, cm], clientele[b, cm], in_module[a, m], in_module[b, m], a < b
consumed[o, mod] := clientele[o, cm], in_module[o, mod]
")

(defn latent-boundaries
  "Bottom-up boundary DISCOVERY (Parnas's decomposition criterion / Interface Segregation, made
   mechanical): code Modules whose PUBLIC surface has split into ≥2 consumer-DISJOINT clienteles — a
   latent sub-interface that has crystallized with its own external clientele but that no formal
   contract names. For each such Module, the discovered sub-interface(s): a bundle of ≥2 public ops
   sharing a clientele, disjoint from the rest of the Module's public surface.

   A SIGNAL for human judgment, NOT a violation (like `module/module-dependencies` /
   `uncovered-calls`): it detects that a seam has crystallized; whether it DESERVES a formal
   split is the human's call (detect-vs-decide). COUNT-INVARIANT by construction: a bundle's clientele
   may grow and the bundle stays disjoint from the rest — so the seam stays visible, unlike a
   single-consumer test which goes silent exactly as a shared internal surface accretes more consumers.

   ON-GRAPH, COMPOSITIONAL — the local `surface` building blocks (`public_op` / `clientele` /
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
  [db-arg]
  (let [base (str eav module/in-module-cozo surface)]
    (if (empty? (db/q db-arg (str base "?[a, b] := co_consumed[a, b]")))
      (sorted-map)
      (->> (db/q db-arg (str base "
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

;; ── model↔code CALL realization readers ──────────────────────────────────────
;; The laws are GENERATED from Operation's :delegates slot options
;; {:realized-by :calls :altitude :container :faithful true} — see canvas.vocab.code.operation.
;; The generated keys are :corresponds/Operation.delegates-realized and
;; :corresponds/Operation.delegates-faithful. The readers below are thin wrappers over
;; s/violations-of (the generic worklist reader).

(defn unrealized-delegates
  "The authored source Operations whose cross-module delegation is NOT realized by any actual call
   between the corresponding modules, as a set of op names. Empty ⇔ every intended module dependency
   is backed by real code. Reads the generated demand (:corresponds/Operation.delegates-realized)."
  [db-arg]
  (cq/violation-names db-arg :corresponds/Operation.delegates-realized))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law, like
   `uncovered-operations`): actual cross-module module-calls (over `:calls`) with no corresponding
   intended cross-module delegation (over `:delegates`, bridged by `module-corresponds?`), as a set
   of [caller-module callee-module] code-module-name pairs. The couplings the design has not yet
   declared. A single on-graph query — actual cross-module calls MINUS those an authored cross-module
   delegation covers (the inner `not-join` is the intended shape the generated delegates-faithful
   demand negates) — under
   Cozo's stratified negation; the empty-relation not-join gotcha that once forced a Clojure
   set-difference is gone. A signal, not a violation: you do not model every call."
  [db-arg]
  (set (cq/q '[:find ?km1 ?km2 :in $
               :where [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
                      [?e1 :val/extracted true] [?e2 :val/extracted true]
                      (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]
                      (not-join [?km1 ?km2]
                        [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                        (authored ?o1)
                        (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
                        [(canvas.vocab.code.module/module-corresponds? ?cm1 ?km1)]
                        [(canvas.vocab.code.module/module-corresponds? ?cm2 ?km2)])]
             db-arg)))

(defn unfaithful-calls
  "The ENFORCED fidelity offenders — extracted caller Operations making an undeclared cross-module
   call between MODELLED faculties, as a set of op names. Empty ⇔ every modelled-faculty coupling in
   the code is declared as intent (so, with DAG-conformance green, the code conforms to the
   `:may-depend` DAG). The modelled-both-ends subset of `uncovered-calls`; reads the generated demand
   (:corresponds/Operation.delegates-faithful)."
  [db-arg]
  (cq/violation-names db-arg :corresponds/Operation.delegates-faithful))
