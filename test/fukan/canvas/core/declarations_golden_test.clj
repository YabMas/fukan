(ns fukan.canvas.core.declarations-golden-test
  "Characterization lock for the kernel's declaration-registry emission: the full set of derived
   Terms (`structure/terms-of`) and Laws (`structure/laws-of`) over fukan's SELF-MODEL vocabulary
   must not CHANGE silently. Not a spec of WHAT the rules are — a snapshot gate on the sole rule
   emitter (both seams dispatch the declaration handlers).

   Scoped to the `fukan.common.vocab.*`/`fukan.common.typing.malli` structures (required below so they
   are all registered), NOT `all-structures` — the global registry also accumulates test fixtures during
   a full run, which would make the snapshot unstable. The kernel-native grammars (the act grammar in
   `fukan.canvas.core.lens`, the reflection meta-grammar in `fukan.canvas.core.reflect`) are NOT in
   scope — this golden locks the reusable `fukan.common` vocab, not the tool's own core grammars."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.cozo.law]                        ; registers the check engine (load side-effect)
            [fukan.canvas.core.structure :as s]
            ;; force the full self-model vocabulary to register
            [fukan.common.vocab.grouping]
            [fukan.common.typing.malli]
            [fukan.common.vocab.code.kind]
            [fukan.common.vocab.code.effect]
            [fukan.common.vocab.code.operation]
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.subsystem]
            ;; …and `Band`, promoted into the shipped vocab 2026-08-28 but never required here —
            ;; so this snapshot passed only in a FULL run, where some other test's composition
            ;; root registered it, and dropped 9 terms + 8 laws when run ALONE. Exactly the
            ;; order-dependence the requires around it were added to prevent; it hid for a day
            ;; because the counts did not move in between, and a golden that is only correct
            ;; when other tests run first is not guarding what it claims to guard.
            [fukan.common.vocab.code.band]
            ;; …the pattern tier too: since the dependency inversion (the pattern names its
            ;; participants, the core never names the pattern — 2026-07-21) NO vocab ns requires it,
            ;; so only an explicit require keeps its 7 terms + 4 laws in the snapshot (the same
            ;; order-dependence lesson as the extraction nss below).
            [fukan.common.vocab.patterns.plug-point]
            ;; …and the Clojure extraction plugin, which since 2026-07-17 declares the design↔Clojure
            ;; CORRESPONDENCE (the fact-slots + demands) against the Operation/Module tags. Without
            ;; these the snapshot silently drops 5 terms + 11 laws — and passes anyway in a full run,
            ;; because some other test's composition root registers them. Order-dependence is exactly
            ;; what this namespace's explicit requires exist to prevent.
            [fukan.common.extraction.clojure.module]
            [fukan.common.extraction.clojure.operation]))

(defn self-model-structures
  "The registered structures defined in the self-model vocabulary — stable regardless of which test
   fixtures are also loaded into the global registry.

   Scoped by the tag's namespace OR, for RELATION elements (whose tag is unqualified because the rule
   name is global), the declaring `:ns`. Before 2026-07-17 this scoped on the tag alone, so every
   relation element escaped the snapshot — the containment rules and the `defrelation`s
   (`module-depends`/`module-owns`/`in-subsystem`) were emitted but never guarded."
  []
  (filter #(when-let [ns (or (namespace (:tag %)) (:ns %))]
             (and (not (str/ends-with? ns "-test"))
                  (str/starts-with? ns "fukan.common")))
          (s/all-structures)))

(defn normalized-terms
  "Every derived Term over the self-model, as a stable sorted-set of pr-str'd rules."
  []
  (into (sorted-set) (map pr-str) (s/terms-of (self-model-structures))))

(defn test-root-disjunct? [d]
  (let [clauses (if (and (seq? d) (= 'and (first d))) (rest d) [d])]
    (some (fn [c]
            (and (vector? c) (= :structure/of (second c))
                 (some-> (namespace (nth c 2)) (str/ends-with? "-test"))))
          clauses)))

(defn normalize-clause [c]
  (if (and (seq? c) (= 'or-join (first c)))
    (let [[_ vars & disjuncts] c
          kept (remove test-root-disjunct? disjuncts)]
      (if (= 1 (count kept))
        (let [d (first kept)]
          (if (and (seq? d) (= 'and (first d))) (vec (rest d)) [d]))
        [(apply list 'or-join vars kept)]))
    [c]))

(defn normalize-law [law]
  (update law :where #(vec (mapcat normalize-clause %))))

(defn normalized-laws
  "Every Law over the self-model, as a stable sorted-set of pr-str'd law data."
  []
  (into (sorted-set)
        (for [sdef (self-model-structures), law (s/laws-of sdef)]
          (let [law (normalize-law law)]
            (pr-str (mapv (fn [k] [k (get law k)])
                          [:key :desc :offenders :where :rules]))))))

(defn snapshot-hash [xs] (hash (vec xs)))

;; The frozen snapshot (captured 2026-07-06 against pre-refactor emission). Any drift in derived
;; Terms/Laws during the Stage-A re-plumb fails here; diff `(normalized-terms)`/`(normalized-laws)`
;; live against the failing set to localize the family whose handler drifted.
;; Laws hash refreshed 2026-07-08: the Operation type-coverage + signature-completeness laws now
;; read `(exposed ?x)` (a new defrelation) instead of the inline `:exposes` EAV — behavior-preserving
;; (the rule expands to the same clauses; live check unchanged at 0 violations), so only the emitted
;; `:where` form moved. Terms unaffected (a defrelation is not a declaration Term).
;; Laws hash refreshed again 2026-07-08: `authored`/`extracted-op` (redundant per-kind aliases of the
;; kernel's universal `design`/`fact` substrate rules) were dissolved, so the signature-completeness +
;; totality laws now read `(design …)` — behavior-preserving (live check still 0 violations, count
;; still 82). Terms unaffected: those defrelations had unqualified tags, already outside this snapshot.
;; Laws hash refreshed once more 2026-07-08: hand-inlined reified-edge triples in law bodies (subsystem
;; :may-depend conformance/acyclicity, TrustBoundary parser-cross-check + totality, layering membership)
;; now read the auto-derived slot rules (`may-depend`/`kind`/`in`/`performs`/`fact`) — behavior-preserving
;; (live check still 0 violations, count still 82), only the emitted `:where` altitude changed.
;; Laws +1 on 2026-07-09: Operation gained the GATED signature-adherence demand
;; `:corresponds/Operation.adheres` (an `(agrees {:by :signature})` pair-hybrid) — count 82→83; the
;; self-model is green (all realizing signatures reconciled to exactly adhere). Terms unaffected.
;; 2026-07-09: the misfiled demands (`signature-completeness` + `no-isolated`) moved OFF Operation's
;; identity into `canvas.principles.operation-surface` (a pure law-holder); `Connected` + the `includes`
;; usage were retired. Terms 52→51 (−Connected kind-rule, −the dead `includes` term, +the holder's
;; kind-rule); laws stay 83 (both demands relocated, re-expressed with explicit `(Operation …)` scope);
;; live `(check)` still 0. Operation's defstructure is now pure slots.
;; 2026-07-09 (cont.): the `includes` mechanism retired — the meta-grammar `Structure` lost its
;; `:includes [:* Structure]` slot, dropping that relation's target-type LAW (83→82) and re-shaping the
;; terms. `(check)` still 0.
;; 2026-07-09: `Contract` introduced (fukan.common.vocab.code.contract) — a Module `:offers` contracts, an
;; Operation `:satisfies` them (the coarse plug-point vocabulary). Terms 50→54 (Contract kind-rule +
;; `offers`/`satisfies` relation rules + contains contribution), laws 82→86 (the new slots' target-type
;; + the `:shape` type-check laws). Live `(check)` still 0.
;; 2026-07-09 (cont.): the contract seam settled at MODULE altitude — `:satisfies` moved off Operation
;; onto Module (a Contract is a bundle a MODULE provides; mirror of `:offers`), and the vestigial
;; `:dispatches-to` slot (zero authored instances — the inversion it fumbled is now the offer/satisfy
;; seam) was dropped from Operation. Terms 54→53 (−the `dispatches-to` relation rule; `satisfies` rule
;; persists on Module), laws 86→85 (−Operation's `:dispatches-to` target-type law; `:satisfies`
;; net-zero, relocated). Live `(check)` still 0. Both contract seams live on Module.
;; 2026-07-09 (cont.): `:guidance` moved off Operation into a kernel-level per-instance ANNOTATION
;; (`reserved-annotation-keys`) authorable on ANY instance and stored as the `:val/guidance` leaf it
;; already was — the read dual of the docstring, not vocab. Terms unchanged (a scalar slot emits no
;; relation rule), laws 85→84 (−Operation's `:guidance` type-check law). Operation is now pure
;; computation {:in :out :performs :delegates}. Live `(check)` still 0.
;; 2026-07-10: `Contract` renamed to `PlugPoint` (fukan.common.vocab.code.plug-point) — the concept was
;; always a plug-point/SPI/dependency-inversion point (its own docstring said so); the neutral name
;; obscured the directionality. Pure rename: counts unchanged (terms 53, laws 84), only the tag in the
;; emitted kind-rule + `:shape` type-check law moved (`Contract`→`PlugPoint`). Live `(check)` still 0.
;; 2026-07-10: `exposed` RETIRED, and a `defmulti` is a POLYMORPHIC OPERATION (not a plug-point). A
;; re-triage settled that PlugPoint means split-ownership inversion (external SPI); render-base/
;; render-finding are united-ownership internal dispatch, so they stay Operations and declare their
;; uniform signature (db,base,eid)->Instruction / (db,proj,focus)->Finding. With every op declaring an
;; `:out`, no non-exposed op lacks one, so the `(exposed ?x)` filter on signature-completeness +
;; type-coverage is vacuous: dropped it from both laws, retired the defrelation, removed its boundary-
;; reader use. Laws count 84 (two `:where` bodies simplified -> only the laws hash moves); terms 53 (the
;; defrelation tag was unqualified, outside this snapshot). "An Operation IS a surface" is now
;; structural, not a filtered law. Live `(check)` still 0.
;; 2026-07-13: signature decomposition step 1 — a type-REFERENCE is now a name leaf (`:ref`), not a
;; var-captured `:names` edge. Schema's `:names` RELATION slot became the `:ref` SCALAR slot, so its
;; derived relation rule is gone (terms 53 -> 52). The Schema "a ref must name a target" presence law
;; was replaced by the "every type-reference resolves to a modelled Kind" no-dangling-ref law (laws
;; count unchanged at 84; only the laws hash moves). Live `(check)` still 0.
;; 2026-07-13: signature decomposition steps 4-5 — the fact-side `:val/sig` BLOB is retired. The `:sig
;; [:? :string]` correspondence slot is dropped (its auto-generated scalar type-check law goes with it:
;; laws 84 -> 83), and the two coverage/adherence gates retarget from `[[?t :val/sig ?_s]]` to the twin
;; declaring an :out (`[[?tr :rel/from ?t] [?tr :rel/kind :out]]`), moving the laws hash. Adherence is
;; now STRUCTURAL — the `:signature` comparator compares decomposed :in/:out node identities. terms
;; unchanged (52). Live `(check)` still 0.
;; 2026-07-13: the malli type dialect moved out of general vocab into its OWN area — `fukan.common.vocab.type`
;; → `fukan.common.typing` (a self-contained plugin: shape-vocab + bridges). Pure relocation: Schema/
;; SchemaChoice/SchemaField still register (the golden filter now also matches `fukan.common.typing`), so counts
;; are unchanged (terms 52, laws 83); only the tag qualifier in the emitted kind/relation rules moved
;; (`fukan.common.vocab.type/*` → `fukan.common.typing.malli/*`), shifting both hashes. Live `(check)` still 0.
;; 2026-07-13: the `canvas/principles/` layer was cut to focus scope on vocab + verification. The three
;; law-holder structures (TrustBoundary, OperationSurface, ModuleArchitecture) and their laws leave the
;; snapshot (terms 52→47, laws 83→74); ModuleArchitecture's two module-graph laws (acyclicity +
;; membership) were REHOMED onto `Subsystem` (so they still fire — the net −9 laws is the genuine
;; principle demands: TrustBoundary totality/parser/cardinality + OperationSurface signature-completeness/
;; no-isolated). Live `(check)` still 0 on the self-model.
;; 2026-07-13: grammar reflection moved out of vocab into its own area — `fukan.common.vocab.grammar` →
;; `fukan.canvas.core.reflect` (a tool, not general vocab). Pure relocation: the meta-grammar
;; (Structure/Law/Vocabulary/Relation) still registers (the golden filter now also matches
;; `fukan.common.reflect`), so counts hold (terms 47, laws 74); only the tag qualifier in the emitted
;; rules moved (`fukan.common.vocab.grammar/*` → `fukan.canvas.core.reflect/*`), shifting both hashes.
;; 2026-07-13: `fukan.common.typing` collapsed into `fukan.common.typing.malli` (one honest malli-named file — the
;; root `typing.clj` holding malli vocab under a generic name was a churn-avoidance wart). Schema/
;; SchemaChoice/SchemaField still register (the filter matches `fukan.common.typing` as a prefix), counts
;; hold (terms 47, laws 74); only the tag qualifier moved (`fukan.common.typing/*` → `fukan.common.typing.malli/*`).
;; 2026-07-14: grammar relocated to the fukan.common.* library tier (canvas.{vocab,typing,
;; extraction,reflect} → fukan.common.\1). Pure relocation: counts hold (47 terms / 74 laws);
;; only the tag qualifier in the emitted rules moved, so both hashes shift. Filter now matches
;; the single `fukan.common` prefix.
;; 2026-07-15: grammar REFLECTION moved out of the fukan.common tier into CORE
;; (`fukan.common.reflect.grammar` → `fukan.canvas.core.reflect`) — it is kernel-native, grammar-agnostic
;; machinery (like the act grammar in core.lens), not the reusable vocab. So its meta-grammar
;; (Structure/Law/Vocabulary/Relation) LEAVES this `fukan.common`-scoped snapshot: terms 47→42
;; (−5 kind/relation rules), laws 74→59 (−15 generated type-check/target-type + 2 self-check laws).
;; The reusable vocab's own emission is unchanged; the golden now locks exactly the fukan.common vocab.
;; 2026-07-16: Module's correspondence bridge became a declarative name-match STRATEGY — the generated
;; twin term changed from `[(…/module-corresponds? ?an ?bn)]` to `[(name-match :qualified-suffix ?an ?bn)]`
;; (the kernel now lowers the match; vocab hand-writes no CozoScript/fn). Count holds (42 — one twin term),
;; only the twin term's predicate changed, so the terms hash shifts; laws unchanged.
;; 2026-07-16 (b): the `:extracted` authoring slot was removed from Module's + Operation's correspondence
;; — provenance is pipeline metadata (`stamp-stratum`), never an authoring slot. Two scalar fact-slots
;; gone ⇒ their two auto-generated boolean type-check laws leave (laws 59→57); terms unchanged (a
;; scalar slot emits no rule term).
;; 2026-07-16 (c): the generated `delegates-realized` law moved from module-altitude/direct to
;; op-altitude/transitive — every cross-module design delegation must be realized by a `:calls+` PATH
;; between the endpoints' OWN twins (the `:altitude` option, a fiction with one legal value, was
;; retired; the hand-written `unrealized-dispatch` query it replaces is gone). Count holds (57 — still
;; one realized + one faithful law); only the realized law's `:where` body + `:desc` changed, moving the
;; laws hash. `delegates-faithful` (module-altitude) is unchanged. Live `(check)` still 0 on the self-model.
;; 2026-07-16 (d): Operation's type-coverage `:require` + adheres `:when` guards moved from raw reified
;; triples `[[?tr :rel/from ?t] [?tr :rel/kind :out]]` to the auto-generated `out` slot-rule
;; `(out ?t ?_o)` — behavior-preserving (the rule expands to the same triples; live check still 0,
;; type-coverage/adheres offenders still 0), only the two laws' `:where` altitude moved. Count holds (57).
;; 2026-07-17: `contains` became a vocab-declared relation ELEMENT. The kernel no longer hardcodes the
;; containment genus, its closure, or `in-module` (code vocabulary in the kernel — `terms-of` emitted
;; all three); a relation's CHARACTER (`:isa <genus>` / `:transitive`) now rides the relation via
;; `defrelation`, not a slot's props, so `:child`'s containment is declared ONCE instead of on all three
;; structures with a `:child` slot. Containment emission is a WASH (7 terms before, 7 after — 4
;; subsumption + 2 closure + in-module), and the live rules are unchanged.
;; The count moves 42→47 for a different reason: `self-model-structures` now scopes by the declaring
;; `:ns` as well as the tag namespace, so the five `defrelation`s (unqualified tags — module-owns/
;; module-depends/in-subsystem/…) enter the snapshot for the FIRST time. They were always emitted, never
;; guarded; this change would otherwise have moved containment into that same blind spot. Laws hold (57 —
;; a relation element declares none).
;; 2026-07-17 (b): correspondence became a CROSS-TAG morphism — `Operation ↦ Fn`, `Module ↦ Ns`, the
;; codomains being real `defstructure`s in the Clojure extraction plugin (fukan.common-scoped, so in
;; snapshot). The fact-side slots (`:calls`/`:private`/`:export`/`:test-support`) that were grafted onto
;; the Operation tag are now `Fn`'s own slots. Terms 47→49: +2 = the new `Fn`/`Ns` kind-rules; every
;; shared relation rule (`in`/`out`/`performs`/`calls`/`calls+`/`child`) dedups, so no other term moves.
;; Laws 57→62: +5 = `Fn`'s own structural laws (in/out/performs/calls target-types + the three boolean
;; type-checks ≈ 8) plus `Ns.child` (1), MINUS the 4 fact-slot laws that used to ride Operation's graft.
;; 2026-07-17 (c): the correspondence IDENTITY map is now DERIVED, not authored. `Operation`'s
;; `(agrees {:key :adheres :by :structural :over [:in :out] :when (out ?t ?_o)})` left the plugin block;
;; the kernel derives the same structural agreement from the slots Operation and Fn share by name+sort
;; (minus the charactered `:performs`), emitting it as `:corresponds/Operation.agrees`. One agree law
;; before and after (count holds at 62), but its `:key` (adheres→agrees), `:desc` (authored prose →
;; derived), and guard var (`?_o`→`?_out`) changed, so only the laws hash moves. Live `(check)` still 0.
;; 2026-07-20: `type-coverage` FOLDED into the `out↦out` identity map. The `(realized {:key
;; :type-coverage :require (out ?t ?_o)})` demand left the plugin block, and the derived agrees dropped
;; its presence guard — structural equality is FORWARD, so a twin missing an :out the design declares is
;; an offender (the folded failure mode). Laws 62→61 (−the type-coverage realized law; the agrees law's
;; `:where` loses its guard clause, moving the hash). Live `(check)` still 0 — every modelled op's twin
;; carries its out on the self-model.
;; 2026-07-20 (b): the relation-map primitive `(rel incl E)`. `performs` moved off the ad-hoc
;; `(performs {:covered-from [:calls* :performs]})` onto `(:performs :sup [:cat [:* :calls] :performs])`
;; — the same coverage law (still `:corresponds/Operation.performs-covered`, same `:where`), but its
;; `:desc` now names the relation-algebra expression `[:cat [:* :calls] :performs]` instead of the
;; `:covered-from` vector. One law before and after (count holds 61); only the desc, so the hash moves.
;; Live `(check)` still 0. (delegates stays on `:realized-by`/`:faithful` until the roll-up step.)
;; 2026-07-20 (c): `delegates` moved off the legacy `{:realized-by :calls :faithful true}` onto the
;; relation-map primitive as the ROLL-UP `(:delegates :sub [:cat :calls [:* [:cat [:test :private] :calls]]])`
;; — the public call graph `calls·(private·calls)*`, PRESERVE only. Terms 49→51: the guarded-closure
;; compiles to a recursive derived rule `delegates-reach` (2 clauses). Laws 61→60: delegates now emits
;; ONE law (op-level realized, over the roll-up) instead of two (the old op-realized + the module-altitude
;; faithful). Fidelity (the retired faithful direction) is an ARCHITECTURAL concern already enforced by
;; Subsystem `:may-depend` conformance. Live `(check)` still 0 (fixed one over-declared self-model edge:
;; model->cozo reached `q` only through public intermediates it already delegates to).
;; 2026-07-20 (d): the OBJECT MAP. `realized`→`:total`, `covered`→`:surjective-onto :fn-public` (bare
;; keyword flags, not sub-forms). The `:private`/`:export`/`:test-support` exemptions moved OFF the
;; correspondence ONTO `Fn`, as the `fn-public` derived predicate the codomain owns. Terms 51→52 (the
;; new `fn-public` rule; unqualified tag, in golden scope via the declaring plugin ns). Laws hold at 60
;; (still one total + one surjective + one agrees node demand), but their keys (`.realized`→`.total`,
;; `.covered`→`.surjective`), descs, and the surjective `:where` (`(fn-public ?x)` instead of the inline
;; `:val/*` exemptions) changed → laws hash moves. Live `(check)` still 0.
;; 2026-07-20 (e): the correspondence is ONE `(correspond …)` block of `design-symbol :incl fact-expr`
;; maps (a signature morphism). The object map is a SORT-map inclusion: `Operation :eq [Fn :public]` and
;; `Module :eq Ns` (was two blocks, with Operation carrying `:total`/`:surjective-onto` flags and Module
;; carrying only a bridge). Terms hold at 52 (the `fn-public` predicate renamed to `public` — same rule).
;; Laws 60→62: `Module :eq Ns` now generates its object map too (a total + a surjective law — Module was
;; previously under-specified, only a twin), and the Operation surjective law reads `(public ?x)` (the
;; renamed predicate). Live `(check)` still 0 (every design Module has an Ns twin and vice-versa).
;; 2026-07-20 (f): reverted to PER-DESIGN-ELEMENT `correspond` blocks (one sort per call — same emission
;; as the single block), and UNIFIED the public/private line: the delegates roll-up interior is now
;; `[:test [:not :public]]` (¬public) instead of the raw `[:test :private]` flag — the SAME `public`
;; predicate the codomain restricts to, complemented. So an unmodelled `^:export`/`^:test-support` helper
;; is interior (routable-through), not a boundary. The `delegates-reach` rule's guard clause changes from
;; `[?m :val/private true]` to `(not (public ?m))` (terms hash), and the delegates realized law's desc
;; names the new expr (laws hash); counts hold (52 / 62). Live `(check)` still 0 (larger interior only
;; realizes MORE delegations).
;; 2026-07-20 (g): the delegates roll-up is now a NAMED fact relation — `public-call` (a recursive
;; `defrelation`: base `(calls a b)` + step `(calls a m) (not (public m)) (public-call m b)`) — and the
;; relation map references it as an atom: `(:delegates :sub :public-call)`. So the inline KAT expression
;; (and the kernel's `guarded-closure`/`test-clause` machinery) is gone; `defrelation` gained multiple
;; rule bodies for recursion. Terms hold at 52 (`public-call`'s 2 rules replace the generated
;; `delegates-reach`'s 2), and laws hold at 62 (the delegates realized law now reads `(public-call …)`
;; with desc naming `:public-call`). Live `(check)` still 0.
;; 2026-07-20 (h): ONE inclusion construct — `defrelation` is bare / `(dir expr)` / derived, and
;; closures are the COMPILER's. `{:isa :contains}` → `(:sub :contains)` (identical subsumption rule,
;; no term change per species); `{:transitive true}` and the `:transitive` slot-option are RETIRED —
;; `terms-of` now emits every binary relation's `R+` closure unconditionally (injected per-query only
;; when referenced, so no per-law cost). Terms 52 → 92 (+40: 20 binary relations × 2 closure rules;
;; `contains+`/`calls+`/`delegates+` were already among them, the rest are newly available); hash
;; moves with the additions. Laws hold at 62 (inclusion lowering emits rules, never laws). Live
;; `(check)` still 0.
;; 2026-07-21: PlugPoint moves to the PATTERN TIER — ns `fukan.common.vocab.patterns.plug-point`
;; (a pattern is a configuration built ON the core code grammar; in the theory frame the file is an
;; ENRICHMENT importing the core's sorts). The `:offers` relation element moves with it (the enriching
;; theory owns the relations it adds), and `:satisfies` becomes a declared element there (bare — the
;; inverted edge, deliberately not a `contains` species; was slot-only). Counts hold (92 / 62): pure
;; relocation — no rule or law added, but the PlugPoint TAG is ns-qualified, so its kind rule and the
;; `:offers`/`:satisfies`/`:shape` target-type laws name the new qualifier → both hashes move.
;; 2026-07-21 (b): `in-module` REHOMED + RENAMED — `:within`, the `contains` genus read by container
;; name, owned by the GROUPING signature (its body mentions only the genus + the substrate's
;; `:entity/name`; nothing about it requires a Module — the old name/home was a historical narrowing).
;; Terms hold at 92 (the derived rule's head renames `in-module`→`within` → hash moves); laws hold at
;; 62 unchanged (no law reads the by-name convenience — they scope via kind rules / `in-subsystem`).
;; 2026-07-21 (c): `PlugPoint` is a CONTRACT and `Module` is CLOSED to the pattern tier. The pattern
;; names its participants — `PlugPoint {:shape :owner}` — and `offers` becomes the DERIVED converse
;; (`(owner ?p ?m)`), no longer a `contains` species; the never-used `:satisfies` slot is cut until
;; the satisfy cycle. Terms 92→91: −offers subsumption −offers slot-rule −satisfies slot-rule
;; +offers derived-rule +owner slot-rule (closures net zero: satisfies+ out, owner+ in). Laws hold
;; at 62: −Module.offers/.satisfies target-type +PlugPoint.owner target-type +owner at-most-one
;; (cardinality `[:? Module]`); hashes move with both.
;; 2026-07-21 (d): `Module` COLLAPSES to a Grouping over code elements — `{:child [:* Operation Kind
;; Module]}` (the first UNION slot target; the union is the membership constraint, its generated
;; disjunctive target-type law the teeth). The `:exposes`/`:owns` role species are CUT (nothing
;; consumed them — the surface doctrine is the morphism's `Operation :eq [Fn :public]`: modelled ⇒
;; public), leaving `:child` the ONE containment species. Kind's ownership law rephrases over the
;; genus ("a member of at most one Module", a kind-home rule-call). Terms 91→83 (−2 species
;; subsumption, −2 slot rules, −4 closures). Laws 62→61 (−2 role target-type laws, +1 union :child
;; target-type — the Any target had none; Kind's law swaps 1:1).
;; 2026-07-21 (e): the `(is ?v Sort)` sort pin lands — declaration-site ns-precise kind guards.
;; The shipped vocab's raw `[?x :structure/of <qualified-tag>]` clauses convert to `(is ?x <tag>)`
;; (resolved at parse — symbol via the declaring ns's vars / self-tag, `::Sort` forward refs, the
;; full keyword where a require would cycle; LOWERED by the query compiler to the same triple or
;; kind-rule call, so evaluation is unchanged): `in-subsystem`/`module-owns`/`public` rule bodies
;; (terms hash), the `declared-dep`/`kind-home` law-local rules + the module-acyclicity and
;; membership-totality `:where` guards (laws hash). Counts hold (83 / 61) — pure clause reshape,
;; stored at domain altitude. Live `(check)` still 0.
;; 2026-07-21 (f): correspondence terminology now matches its relational semantics. The generated
;; carrier-coverage laws say left-total/right-total and correspondent, not object-map total/
;; surjective/preimage (which falsely suggested functionality). Law bodies, keys, and count hold;
;; only the four Operation/Module coverage descriptions move the laws hash. Live `(check)` still 0.
;; 2026-07-21 (g): correspondence carriers become ordinary named `defrelation`s (`module-twin`,
;; `operation-twin`) and `correspond` only aliases each into the shared `twin` relation while deriving
;; explicit coverage laws. Terms 83→89: each binary derived carrier contributes its rule + two
;; compiler-owned closure rules. Laws hold at 61: `:coverage :both` generates the same two denials and
;; relation maps are unchanged. The kernel no longer hardcodes name/containment matching.
;; 2026-07-23: the essential `correspond` construct's term emission lands. `terms-of` now adds, per
;; `^:value` structure, a `corresponds(?v ?v)` REFLEXIVITY rule (a content-identified value is its own
;; pairing witness) plus the pairing/`realized-*` rules of every registered NEW-STYLE correspondence.
;; The self-model registers none of the new construct yet (its two correspondences still ride the
;; renamed `correspond-legacy` seam, whose emission is untouched), so the only delta is the four
;; reflexivity rules for the self-model's `^:value` sorts — Schema/SchemaChoice/SchemaField (malli) +
;; Effect. Terms 89→93 (+4 reflexivity); laws hold at 61 (these rules are definitional — no denials).
;; 2026-07-23 (b): the seam CUTOVER — the self-model's two correspondences move from `correspond-legacy`
;; to the essential `correspond` (operation.clj: `[Operation ?op Fn ?fn]`; module.clj: `[Module ?m Ns ?ns]`).
;; Terms 93→90 (−3): OUT −12 = the two `twin` alias rules + `operation-twin`/`module-twin` derived rules
;; (1 each) + their `R+` closures (2 each) + `public-call`'s 2 rules + its `R+` closure (2); IN +9 = the
;; two `corresponds` pairing rules + `realized-{in,out,performs,delegates,child}` + the `realized-delegates-s1`
;; aux pair (base+step, the ex-`public-call` roll-up minted as a compound-closure aux). The four `^:value`
;; reflexivity rules stay. Laws 61→54 (−7): every generated `:corresponds/*` demand dissolves —
;; Operation.{total,surjective,agrees,delegates-realized,performs-covered} (5) + Module.{total,surjective}
;; (2); NONE added (the essential construct is definitional — no denials). Coverage/adherence are now
;; READINGS (`drift`/`encapsulation`/`type-drift` in dev/user.clj), not laws. Live `(check)` still 0.
;; 2026-07-23 (c): fixed a latent Task-2 emission bug the cutover's readings surfaced —
;; `realized-delegates`'s roll-up `[:cat :calls [:* [:cat [:not public] :calls]]]` lowered (after aux
;; extraction) to `[:cat :calls [:? aux]]`, whose TRAILING zero-admitting step emitted a bare `(= a b)`
;; unification in a standalone or-join helper Cozo cannot ground (`unbound variable` — the rule was
;; unqueryable, uncaught because no law evaluates it post-cutover). `distribute-trailing-closure` now
;; rewrites a `:cat` ending in `[:? X]`/`[:* X]` to `[:alt prefix (prefix++X|X+)]`, so the reflexive
;; case grounds `to` through a relation. Only `realized-delegates`'s body changes (its or-join branches
;; move from `(= …)`/`(aux … (= …))` to `(calls …)`/`(calls … aux)`); term COUNT holds at 90, laws at
;; 54 — only the terms hash moves.
;; 2026-08-20: the ADOPTION frontier — `(defrelation :adopted [?ns] …)` in the Clojure extraction
;; plugin (module.clj) names the Ns half of a live `Module ↦ Ns` pairing, so the coverage readings can
;; be relativized to the region a project has actually claimed instead of asserting total coverage over
;; the whole codebase. Terms 90→91 (+1): one rule for `adopted`, and NO closure — closures are minted
;; per BINARY relation and this one is unary. Laws hold at 54: a defrelation declares no denial, and
;; adoption coverage stays a READING (an unclaimed namespace is not an offender). The frontier readers
;; themselves (`ns-dependencies`/`adoption-frontier`/`adoption-candidates`) are ordinary Clojure over
;; single-relation pulls, so they emit no terms at all — deliberately, see `ns-dependencies` on why the
;; three-way join is not expressible in datalog at a usable cost.
;; 2026-08-25: the substrate fix restores `ns-depends` as a real defrelation. It was withheld at
;; 91 because the three-way join it expresses cost 58-69s through the old unified `triple` view —
;; a 60-second landmine for any law that read it. With every clause compiling to DIRECT
;; stored-relation access that same join is 0.29s on clojure-mcp (1.20s on babel, down from
;; 591.6s), so the relation is safe to declare and coverage stays pure datalog. Terms 91→94 (+3):
;; the `ns-depends` rule plus the compiler-minted `R+` closure pair (it is BINARY, unlike the unary
;; `adopted`). Laws hold at 54 — a defrelation declares no denial.
;; 2026-08-28: `Band` promoted into the shipped vocab (fukan.common.vocab.code.band) — the
;; namespace-prefix stratum whose evidence is the extracted call graph, nido's element made
;; reusable. Terms 94→103 (+9): two kind rules (Band, NsPrefix), NsPrefix's `^:value`
;; reflexivity, the `in-band` defrelation plus its compiler-minted closure pair, and the
;; `prefix` slot rule plus ITS closure pair. `may-depend` adds NOTHING — Subsystem already
;; declares a slot of that name and the emitted rule is keyed on `:rel/kind`, so the two forms
;; are identical and dedup. Laws 54→62 (+8): five GENERATED (prefix target-type + at-least-one,
;; may-depend target-type, value cardinality + :string) and three AUTHORED (cross-band
;; conformance, coverage-once-any-band-is-declared, acyclicity).
;; 2026-08-29: the correspondence grows TEETH. Coverage was a set of readings nothing enforced
;; — a renamed function left the model claiming an Operation nothing realized, and every gate
;; stayed green — so the two "the design claims something the code does not have" readings become
;; gated laws on the codomain structures (`correspond` itself lowers only to rules, so a denial
;; about a correspondence rides the codomain declared beside it). Terms hold at 103: a law
;; declares no rule. Laws 62→64 (+2): module-unrealized on `Ns`, operation-unrealized on `Fn`.
(def ^:private golden-terms {:count 103 :hash 2063935565})
(def ^:private golden-laws  {:count 64 :hash -387397092})

(deftest terms-are-stable
  (let [terms (normalized-terms)]
    (is (= (:count golden-terms) (count terms)) "self-model Term count changed")
    (is (= (:hash golden-terms) (snapshot-hash terms))
        "derived Terms changed — emission must be behavior-preserving")))

(deftest laws-are-stable
  (let [laws (normalized-laws)]
    (is (= (:count golden-laws) (count laws)) "self-model Law count changed")
    (is (= (:hash golden-laws) (snapshot-hash laws))
        "derived Laws changed — emission must be behavior-preserving")))
