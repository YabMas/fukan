(ns fukan.canvas.core.declarations-golden-test
  "Characterization lock for the kernel's declaration-registry emission: the full set of derived
   Terms (`structure/terms-of`) and Laws (`structure/laws-of`) over fukan's SELF-MODEL vocabulary
   must not CHANGE silently. Not a spec of WHAT the rules are — a snapshot gate on the sole rule
   emitter (both seams dispatch the declaration handlers).

   Scoped to the `fukan.common.vocab.*`/`fukan.common.typing.malli`/`fukan.common.reflect` structures (required below so they are all
   registered), NOT `all-structures` — the global registry also accumulates test fixtures during a
   full run, which would make the snapshot unstable."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.cozo.law]                        ; registers the check engine (load side-effect)
            [fukan.canvas.core.structure :as s]
            ;; force the full self-model vocabulary to register
            [fukan.common.vocab.grouping]
            [fukan.common.typing.malli]
            [fukan.common.reflect.grammar]
            [fukan.common.vocab.code.kind]
            [fukan.common.vocab.code.effect]
            [fukan.common.vocab.code.operation]
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.subsystem]))

(defn self-model-structures
  "The registered structures defined in the self-model vocabulary — stable regardless of which test
   fixtures are also loaded into the global registry."
  []
  (filter #(when-let [ns (namespace (:tag %))]
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
;; `fukan.common.reflect.grammar` (a tool, not general vocab). Pure relocation: the meta-grammar
;; (Structure/Law/Vocabulary/Relation) still registers (the golden filter now also matches
;; `fukan.common.reflect`), so counts hold (terms 47, laws 74); only the tag qualifier in the emitted
;; rules moved (`fukan.common.vocab.grammar/*` → `fukan.common.reflect.grammar/*`), shifting both hashes.
;; 2026-07-13: `fukan.common.typing` collapsed into `fukan.common.typing.malli` (one honest malli-named file — the
;; root `typing.clj` holding malli vocab under a generic name was a churn-avoidance wart). Schema/
;; SchemaChoice/SchemaField still register (the filter matches `fukan.common.typing` as a prefix), counts
;; hold (terms 47, laws 74); only the tag qualifier moved (`fukan.common.typing/*` → `fukan.common.typing.malli/*`).
;; 2026-07-14: grammar relocated to the fukan.common.* library tier (canvas.{vocab,typing,
;; extraction,reflect} → fukan.common.\1). Pure relocation: counts hold (47 terms / 74 laws);
;; only the tag qualifier in the emitted rules moved, so both hashes shift. Filter now matches
;; the single `fukan.common` prefix.
(def ^:private golden-terms {:count 47 :hash -1720407876})
(def ^:private golden-laws  {:count 74 :hash 961294089})

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
