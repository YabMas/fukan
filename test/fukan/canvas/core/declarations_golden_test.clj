(ns fukan.canvas.core.declarations-golden-test
  "Characterization lock for the kernel's declaration-registry emission: the full set of derived
   Terms (`structure/terms-of`) and Laws (`structure/laws-of`) over fukan's SELF-MODEL vocabulary
   must not CHANGE silently. Not a spec of WHAT the rules are — a snapshot gate on the sole rule
   emitter (both seams dispatch the declaration handlers).

   Scoped to the `canvas.vocab.*`/`canvas.principles.*` structures (required below so they are all
   registered), NOT `all-structures` — the global registry also accumulates test fixtures during a
   full run, which would make the snapshot unstable."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.cozo.law]                        ; registers the check engine (load side-effect)
            [fukan.canvas.core.structure :as s]
            ;; force the full self-model vocabulary to register
            [canvas.vocab.grouping]
            [canvas.vocab.type]
            [canvas.vocab.grammar]
            [canvas.vocab.code.kind]
            [canvas.vocab.code.effect]
            [canvas.vocab.code.operation]
            [canvas.vocab.code.module]
            [canvas.vocab.code.subsystem]
            [canvas.principles.parse-dont-validate]
            [canvas.principles.declared-effects]
            [canvas.principles.layered-architecture]
            [canvas.principles.deep-modules]
            [canvas.principles.operation-surface]))

(defn self-model-structures
  "The registered structures defined in the self-model vocabulary — stable regardless of which test
   fixtures are also loaded into the global registry."
  []
  (filter #(when-let [ns (namespace (:tag %))]
             (and (not (str/ends-with? ns "-test"))
                  (or (str/starts-with? ns "canvas.vocab")
                      (str/starts-with? ns "canvas.principles"))))
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
;; 2026-07-09: `Contract` introduced (canvas.vocab.code.contract) — a Module `:offers` contracts, an
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
;; 2026-07-10: `Contract` renamed to `PlugPoint` (canvas.vocab.code.plug-point) — the concept was
;; always a plug-point/SPI/dependency-inversion point (its own docstring said so); the neutral name
;; obscured the directionality. Pure rename: counts unchanged (terms 53, laws 84), only the tag in the
;; emitted kind-rule + `:shape` type-check law moved (`Contract`→`PlugPoint`). Live `(check)` still 0.
(def ^:private golden-terms {:count 53 :hash 1979366480})
(def ^:private golden-laws  {:count 84 :hash -513110322})

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
