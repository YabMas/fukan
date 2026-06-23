(ns canvas.vocab.code.subsystem
  "Code vocab — `Subsystem`: a cluster of Modules realizing a capability, the rung above Module,
   plus the clean-architecture QUALITY layer over the module/subsystem graph: `ModuleArchitecture`
   (no-mutual-dependency + `:may-depend` conformance / acyclicity / membership) and the
   `latent-boundaries` interface-segregation reading. Strength-2 design opinions, beyond consistency."
  (:require [clojure.set :as set]
            [datascript.core :as d]
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

   NO MUTUAL DEPENDENCY: no two Modules may depend on each other (M depends on N and N on M), where
   `module-depends` is the complete graph (calls ∪ data-adoption). `:scope :global` — the offenders are
   the Modules in the cycle; naturally vacuous when no Modules are modelled. (Full TRANSITIVE acyclicity
   needs a self-recursive `reaches`-over-`module-depends` rule, which `check-law-recursion!` rejects;
   this 2-cycle law is the cleanly-expressible first cut.)

   The `:rules` below INLINE `module/module-depends-rules` (a law's `:rules` is macro-time literal data
   and cannot reference the var) — keep the two copies in sync."
  (law "no two modules mutually depend"
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
              (module-owns ?n ?k) [(not= ?m ?n)]]]
    :where '[(module-depends ?m ?n) (module-depends ?n ?m)])

  ;; CONFORMANCE — every cross-subsystem module dependency must follow a declared :may-depend edge.
  ;; Inlines module/module-depends-rules (sync point) + in-subsystem / declared-dep. No rule is
  ;; self-recursive → passes check-law-recursion!. Offender = the module whose dep crosses an
  ;; undeclared subsystem boundary. Vacuous when no Subsystems / no cross-subsystem deps exist.
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
  ;; is PURELY self-recursive (follows :may-depend, calls only itself) — which passes check-law-recursion!.
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

(def ^:private in-module-rules
  "`in-module ?op ?module-name` — an op belongs to a module via :exposes ∪ :owns ∪ :child."
  '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]])

(defn- consumer-components
  "Partition `ops` (op-eids) into connected components: two ops are linked iff their external-consumer
   module-sets (`consumers`: op → #{module}) INTERSECT. A component is thus a maximal set of public ops
   that share a clientele; distinct components are mutually consumer-DISJOINT by construction. Returns
   a vector of sets of op-eids. O(n²) over the few ops a module exposes."
  [ops consumers]
  (reduce (fn [comps o]
            (let [cs (consumers o)
                  {hit true miss false}
                  (group-by (fn [comp] (boolean (some #(seq (set/intersection cs (consumers %))) comp)))
                            comps)]
              (conj (vec miss) (apply set/union #{o} hit))))
          []
          ops))

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

   Method, over the EXTRACTED call graph (`:val/extracted true` + `:calls`): a public op's clientele =
   the OTHER code modules that call it; two public ops are co-consumed when their clienteles overlap;
   connected components of the co-consumed graph are the candidate sub-interfaces. A Module is reported
   when it has a component of ≥2 ops (the COHESION gate) that is a PROPER subset of the Module's
   externally-consumed public surface. Returns a sorted map
   `{module-name [{:ops [name…] :clientele [module-name…]} …]}`; empty ⇔ no module's public surface
   has split into disjoint clienteles."
  [db]
  (let [pub      (d/q '[:find ?o ?on ?km :in $ %
                        :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true]
                               (not [?o :val/private true])
                               [?o :entity/name ?on] (in-module ?o ?km)]
                      db in-module-rules)
        owner    (into {} (map (fn [[o _ km]] [o km])) pub)        ; public op-eid → owning module name
        name-of  (into {} (map (fn [[o on _]] [o on])) pub)        ; public op-eid → op name
        callcons (d/q '[:find ?to ?fkm :in $ %
                        :where [?c :rel/kind :calls] [?c :rel/from ?from] [?c :rel/to ?to]
                               (in-module ?from ?fkm)]
                      db in-module-rules)
        consumers (reduce (fn [m [to fkm]]
                            (if-let [okm (owner to)]               ; ?to is a public op of some module
                              (if (= fkm okm) m (update m to (fnil conj #{}) fkm))  ; external callers only
                              m))
                          {} callcons)
        by-mod   (group-by owner (keys consumers))]               ; module → its externally-consumed public ops
    (into (sorted-map)
          (keep (fn [[km ops]]
                  (let [total  (count ops)
                        proper (->> (consumer-components ops consumers)
                                    (filter #(and (>= (count %) 2) (< (count %) total)))
                                    (sort-by count >))]
                    (when (seq proper)
                      [km (mapv (fn [comp]
                                  {:ops       (sort (map name-of comp))
                                   :clientele (sort (apply set/union (map consumers comp)))})
                                proper)])))
                by-mod))))
