(ns canvas.principles.layered-architecture
  "PRINCIPLE — layered, intentional architecture (Ousterhout APoSD; DDD bounded contexts;
   Parnas information hiding).

   Dependencies are DECLARED and DIRECTED: subsystems form a `:may-depend` DAG the extracted
   call graph must conform to (`ModuleArchitecture`: no mutual module dependencies,
   conformance, acyclicity, membership); the code call graph realizes the declared design
   (`CallRealization`, `Fidelity`); `latent-boundaries` discovers bottom-up boundaries the
   design hasn't drawn yet (Parnas/ISP consumer-disjointness — the information-hiding
   flavour). Judgment readers: `uncovered-calls`, `unfaithful-calls`, `unrealized-delegates`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :refer [eav]]
            [fukan.canvas.core.rules :as rules]
            [canvas.vocab.code.module :as module]))

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

;; ── model↔code CALL realization laws and readers ─────────────────────────────

(defstructure CallRealization
  "Law-holder for the model↔code CALL realization — no instances of its own; the relation-level dual
   of the op-level `Realization`. The INTERPRETATION seam between INTENT (`:delegates`, authored) and
   FACT (`:calls`, extracted), at MODULE-DEPENDENCY altitude: every authored CROSS-MODULE delegation
   must be realized by SOME actual cross-module call between the corresponding modules
   (`module-corresponds?`). Module-level, not exact op-pair: real dependencies are often indirect
   (dispatch, internal leaves) — the author sketches the module dependency on an exposed op.
   `:scope :global` (offenders are authored delegation source ops). Vacuity guard: extraction happened
   ⟺ ≥1 extracted Module, so the law guards on the extracted-Module set (~14), NOT on `:calls` — an
   earlier `[?anycall :rel/kind :calls]` datom guard bound an unused var to every call (~202×),
   cartesian-multiplying the whole law (~20s of `check`). Negation via an inline `not-join` with the
   corresponding-module names bound on entry (mirroring the op-level `Realization` law) keeps
   `?cm1`/`?cm2` bound, avoiding a free-variable blow-up. The `:rules` inline `in-module`
   (self-contained — the inlined-`:rules` convention)."
  (law "every intended cross-module delegation is realized by an actual call between the corresponding modules"
    :scope :global
    :offenders '[?o1]
    :rules '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]]
    ;; vacuity guard on the extracted-Module set (~14), NOT on :calls (~202): an earlier
    ;; `[?anycall :rel/kind :calls]` bound an unused var to every call, cartesian-multiplying the law.
    :where '[[?_xm :structure/of :canvas.vocab.code.module/Module] [?_xm :val/extracted true]
             [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
             (not [?o1 :val/extracted true])
             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
             (not-join [?cm1 ?cm2]
               [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
               [?e1 :val/extracted true] [?e2 :val/extracted true]
               (in-module ?e1 ?km1) (in-module ?e2 ?km2)
               [(canvas.vocab.code.module/module-corresponds? ?cm1 ?km1)]
               [(canvas.vocab.code.module/module-corresponds? ?cm2 ?km2)])]))

(defstructure Fidelity
  "Law-holder for code-up FIDELITY — the ENFORCED dual of the `uncovered-calls` query. Every actual
   cross-module call BETWEEN MODELLED faculties must be covered by an intended `:delegates`. Scoped to
   modelled-both-ends: a call into an UNMODELLED namespace is a coverage gap (the `uncovered-calls`
   query), NOT a fidelity violation — we only enforce boundaries we have claimed to model (a code
   module is modelled when an authored faculty module `module-corresponds?` it). With THIS law green
   AND the `subsystem` DAG-conformance (over `:delegates`) green, the actual code call graph provably
   conforms to the declared `:may-depend` DAG — the architecture finally bites on code. `:scope
   :global` (offenders are the extracted caller ops). Naturally vacuous on a model-only build — the body
   requires extracted cross-module `:calls`, of which there are none without extraction (an earlier
   `[?anydel :rel/kind :delegates]` guard added only a ~30× cartesian multiply, ~3.7s of `check`);
   negation via inline not-join with `?km1`/`?km2` bound on entry (no free-variable blow-up); the
   `intended` rule inlines `in-module` (the inlined-`:rules` convention)."
  (law "every actual cross-module call between modelled faculties is covered by an intended delegation"
    :scope :global
    :offenders '[?e1]
    :rules '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(intended ?km1 ?km2)
              [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
              (not [?o1 :val/extracted true])
              (in-module ?o1 ?c1) (in-module ?o2 ?c2)
              [(canvas.vocab.code.module/module-corresponds? ?c1 ?km1)]
              [(canvas.vocab.code.module/module-corresponds? ?c2 ?km2)]]]
    ;; no vacuity guard needed: the body REQUIRES extracted cross-module :calls, so it is naturally
    ;; vacuous on a model-only build. An earlier `[?anydel :rel/kind :delegates]` guard added only a
    ;; ~30× cartesian multiply.
    :where '[[?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
             [?e1 :val/extracted true] [?e2 :val/extracted true]
             (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]
             [?am1 :structure/of :canvas.vocab.code.module/Module] (not [?am1 :val/extracted true]) [?am1 :entity/name ?cm1]
             [(canvas.vocab.code.module/module-corresponds? ?cm1 ?km1)]
             [?am2 :structure/of :canvas.vocab.code.module/Module] (not [?am2 :val/extracted true]) [?am2 :entity/name ?cm2]
             [(canvas.vocab.code.module/module-corresponds? ?cm2 ?km2)]
             (not (intended ?km1 ?km2))]))

(defn unrealized-delegates
  "The authored source Operations whose cross-module delegation is NOT realized by any actual call
   between the corresponding modules, as a set of op names. Empty ⇔ every intended module dependency
   is backed by real code. Reads the single source of truth (the registered CallRealization law)."
  [db-arg]
  (let [desc (-> (s/structure-by-tag ::CallRealization) :laws first :desc)]
    (->> (s/check db-arg)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db-arg %)))
         set)))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law, like
   `uncovered-operations`): actual cross-module module-calls (over `:calls`) with no corresponding
   intended cross-module delegation (over `:delegates`, bridged by `module-corresponds?`), as a set
   of [caller-module callee-module] code-module-name pairs. The couplings the design has not yet
   declared. A single on-graph query — actual cross-module calls MINUS those an authored cross-module
   delegation covers (the inner `not-join` is the `intended` shape the Fidelity law inlines) — under
   Cozo's stratified negation; the empty-relation not-join gotcha that once forced a Clojure
   set-difference is gone. A signal, not a violation: you do not model every call."
  [db-arg]
  (set (cq/q '[:find ?km1 ?km2 :in $ %
               :where [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
                      [?e1 :val/extracted true] [?e2 :val/extracted true]
                      (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]
                      (not-join [?km1 ?km2]
                        [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                        (not [?o1 :val/extracted true])
                        (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
                        [(canvas.vocab.code.module/module-corresponds? ?cm1 ?km1)]
                        [(canvas.vocab.code.module/module-corresponds? ?cm2 ?km2)])]
             db-arg rules/substrate-rules)))

(defn unfaithful-calls
  "The ENFORCED fidelity offenders — extracted caller Operations making an undeclared cross-module
   call between MODELLED faculties, as a set of op names. Empty ⇔ every modelled-faculty coupling in
   the code is declared as intent (so, with DAG-conformance green, the code conforms to the
   `:may-depend` DAG). The modelled-both-ends subset of `uncovered-calls`; reads the registered
   Fidelity law (the single source of truth)."
  [db-arg]
  (let [desc (-> (s/structure-by-tag ::Fidelity) :laws first :desc)]
    (->> (s/check db-arg)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db-arg %)))
         set)))
