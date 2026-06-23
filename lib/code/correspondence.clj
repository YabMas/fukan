(ns lib.code.correspondence
  "The model↔code CORRESPONDENCE concern — deliberately separate from BOTH the
   abstract modelling domain (canvas/, e.g. `Operation`) and the code-structure domain
   (`Operation`). A domain definition's laws should concern only that domain's own
   behaviour and constraints; HOW the model is realized in code is an orthogonal
   question. Keeping it here means each can be reasoned about — and evolved — in
   isolation: focus on the domain without implementation noise, and focus on the
   implementation/correspondence question without touching the domains.

   It holds fukan's self-correspondence — THE model↔code drift-check (distinct from
   the projected architecture overview, which renders fukan's shape but checks nothing
   against extracted reality). fukan's projection convention is that an
   op-layer `Operation` named X in a canvas module is realized by a function named X in
   the CORRESPONDING code module — so the law matches on name AND module placement
   across the altitude gap between authored Operations (canvas/) and extracted Operations
   (src/), no `realizes` relation needed. Authored and extracted operations are both the
   one `:Operation` tag now (provenance `:extracted`, not tag, distinguishes them); the law
   references it only as data (a structure tag resolved at check-time over the merged graph), so this
   concern takes no code dependency on either domain."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.rules :as rules]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            [lib.code :as code]))

(defn ^:export module-corresponds?
  "True when code namespace `km` realizes canvas module `cm`. Deterministic, separator-agnostic:
   split both on `[-.]` into segments; the canvas name's segments must be a SUFFIX of the code
   namespace's. So `infra-model` ← `fukan.infra.model`, `canvas-source` ←
   `fukan.canvas.projection.canvas-source`, `core-structure` ← `fukan.canvas.core.structure`.
   (Canvas module names are hyphenated and equal their vars; the code path is dotted — this rule
   bridges the two without the model authoring a second name string.)"
  [cm km]
  (let [segs #(str/split % #"[-.]")
        c    (segs cm)]
    (= c (take-last (count c) (segs km)))))

(defn ^:export reader-realizes-lens?
  "True when a bespoke probe reader named `rn` (e.g. \"probe-survey\") realizes the Lens named
   `ln` (\"survey\") — the reader name is the lens name under the `probe-` realization prefix.
   The Lens↔reader analog of `module-corresponds?`: a NAME bridge, so the correspondence needs no
   `:realizes` relation (mirroring `Realization`'s authored↔extracted name match)."
  [rn ln]
  (= rn (str "probe-" ln)))

(defstructure Realization
  "A law-holder for the model↔code correspondence — it has no instances of its own;
   it exists to carry the cross-layer assertion in its own concern.

   `:scope :global` opts out of the default self-scoping (its offenders are Operations,
   not Realizations). The leading extracted-Operation clause is the real guard: the law
   is vacuous when no code is extracted — correspondence is only assertable when both
   layers share the graph — so registering it never disturbs a model-only `check`.

   AUTHORED and EXTRACTED operations are both `Operation`s now — provenance (`:extracted`),
   not tag, distinguishes them. The match is on name AND module: an authored Operation in
   canvas module C is realized only by an extracted Operation of the same name whose code
   module corresponds to C (module-corresponds?)."
  ;; Reads over the vocab-derived rules (check injects them) — domain altitude:
  ;; `(Operation …)`, `(named …)`, `(in-module …)`; `:val/extracted` splits the two sides.
  (law "every authored operation is realized by an extracted operation of the same name in the corresponding module"
    :scope :global
    :offenders '[?s]
    :where '[(Operation ?x) [?x :val/extracted true]                  ; guard: some code is extracted
             (Operation ?s) (not [?s :val/extracted true])            ; an authored operation
             (named ?s ?n) (in-module ?s ?cmn)
             (not-join [?n ?cmn]
               (Operation ?o) [?o :val/extracted true]
               (named ?o ?n) (in-module ?o ?kmn)
               [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)])]))

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
   (self-contained, the `lib.arch` convention)."
  (law "every intended cross-module delegation is realized by an actual call between the corresponding modules"
    :scope :global
    :offenders '[?o1]
    :rules '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]]
    ;; vacuity guard on the extracted-Module set (~14), NOT on :calls (~202): an earlier
    ;; `[?anycall :rel/kind :calls]` bound an unused var to every call, cartesian-multiplying the law.
    :where '[[?_xm :structure/of :lib.code/Module] [?_xm :val/extracted true]
             [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
             (not [?o1 :val/extracted true])
             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
             (not-join [?cm1 ?cm2]
               [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
               [?e1 :val/extracted true] [?e2 :val/extracted true]
               (in-module ?e1 ?km1) (in-module ?e2 ?km2)
               [(lib.code.correspondence/module-corresponds? ?cm1 ?km1)]
               [(lib.code.correspondence/module-corresponds? ?cm2 ?km2)])]))

(defstructure Fidelity
  "Law-holder for code-up FIDELITY — the ENFORCED dual of the `uncovered-calls` query. Every actual
   cross-module call BETWEEN MODELLED faculties must be covered by an intended `:delegates`. Scoped to
   modelled-both-ends: a call into an UNMODELLED namespace is a coverage gap (the `uncovered-calls`
   query), NOT a fidelity violation — we only enforce boundaries we have claimed to model (a code
   module is modelled when an authored faculty module `module-corresponds?` it). With THIS law green
   AND the `lib.arch` DAG-conformance (over `:delegates`) green, the actual code call graph provably
   conforms to the declared `:may-depend` DAG — the architecture finally bites on code. `:scope
   :global` (offenders are the extracted caller ops). Naturally vacuous on a model-only build — the body
   requires extracted cross-module `:calls`, of which there are none without extraction (an earlier
   `[?anydel :rel/kind :delegates]` guard added only a ~30× cartesian multiply, ~3.7s of `check`);
   negation via inline not-join with `?km1`/`?km2` bound on entry (no free-variable blow-up); the
   `intended` rule inlines `in-module` (the `lib.arch` convention)."
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
              [(lib.code.correspondence/module-corresponds? ?c1 ?km1)]
              [(lib.code.correspondence/module-corresponds? ?c2 ?km2)]]]
    ;; no vacuity guard needed: the body REQUIRES extracted cross-module :calls, so it is naturally
    ;; vacuous on a model-only build. An earlier `[?anydel :rel/kind :delegates]` guard added only a
    ;; ~30× cartesian multiply.
    :where '[[?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
             [?e1 :val/extracted true] [?e2 :val/extracted true]
             (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]
             [?am1 :structure/of :lib.code/Module] (not [?am1 :val/extracted true]) [?am1 :entity/name ?cm1]
             [(lib.code.correspondence/module-corresponds? ?cm1 ?km1)]
             [?am2 :structure/of :lib.code/Module] (not [?am2 :val/extracted true]) [?am2 :entity/name ?cm2]
             [(lib.code.correspondence/module-corresponds? ?cm2 ?km2)]
             (not (intended ?km1 ?km2))]))

(defstructure Encapsulation
  "Law-holder for code-up ENCAPSULATION — the ENFORCED dual of the `uncovered-public-operations`
   query, at OPERATION altitude (the op-level peer of the relation-level `Fidelity`). Every PUBLIC
   extracted operation must be COVERED by the model (an authored twin of the same name in the
   corresponding module) OR deliberately exempt:
     - `:val/private`      — an internal (encapsulation working as intended)
     - `:val/export`       — public for MECHANISM (macro-emitted substrate / a var reached only via
                             dynamic dispatch: a datalog predicate, a `(syntax …)`/reader hook)
     - `:val/test-support` — public only for TEST isolation/setup (never called from production)
   A public, non-exempt, unmodelled function is an UNDECLARED PUBLIC SURFACE: the model must name it
   (intent), or it must be hidden. With THIS law green AND `Realization`/`CallRealization`/`Fidelity`
   green, the WHOLE public code surface is provably accounted for by the model.

   `:scope :global` (offenders are the extracted Operations, not Encapsulation). Vacuous on a
   model-only build (the leading `:val/extracted true` clause — no extracted ops, no offenders).
   Module membership + the authored-twin lookup resolve through the injected `in-module` rule,
   mirroring the `Realization` law (the not-join keeps `?on`/`?kmn` bound on entry — no free-variable
   blow-up; the authored side ranges over the finitely-many same-named authored ops)."
  (law "every public extracted operation is covered by the model or deliberately exempt"
    :scope :global
    :offenders '[?o]
    :where '[[?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?on]
             (not [?o :val/private true])
             (not [?o :val/export true])
             (not [?o :val/test-support true])
             (in-module ?o ?kmn)
             (not-join [?on ?kmn]
               [?s :structure/of :lib.code/Operation] (not [?s :val/extracted true]) [?s :entity/name ?on]
               (in-module ?s ?cmn)
               [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)])]))

(defstructure Totality
  "Law-holder for code-up TOTALITY — the ENFORCED dual of the partiality worklist, at the TRUST LINE
   (parse-don't-validate). A trusted-core READER is a modelled Operation whose `:in` signature
   references the trust artifact `StructureDb` (the Model) — it operates ON the trusted graph.
   Parse-don't-validate confines partiality to the layer that BUILDS the Model from untrusted input, so
   a reader operating on the already-trusted graph must be TOTAL: it may not throw. An offender is such
   a reader whose extracted twin (same name, corresponding module — `module-corresponds?`, as
   `Realization`) performs `:throws`. With THIS law green AND `Realization`/`CallRealization`/`Fidelity`/
   `Encapsulation` green, the modelled trusted core is provably total — partiality is confined to the
   Model-building boundary.

   `:scope :global` (offenders are the authored reader ops, not Totality). Naturally vacuous on a
   model-only build (the `[?e :val/extracted true]` + `:performs :throws` clauses find nothing without
   extracted code). The trust artifact is identified STRUCTURALLY (the `Kind` named `StructureDb`) — no
   per-op flag; the `:in`-reaches-`StructureDb` join is the data-adoption join from
   `lib.code/module-depends-rules`. Module membership resolves through the injected `in-module` rule
   (mirroring `Realization`); coverage grows as more op signatures are modelled."
  (law "every trusted-core reader (its :in is the Model) is total — its realizing code performs no :throws"
    :scope :global
    :offenders '[?o]
    :where '[[?o :structure/of :lib.code/Operation] (not [?o :val/extracted true]) [?o :entity/name ?on]
             [?ir :rel/from ?o] [?ir :rel/kind :in] [?ir :rel/to ?sch]
             [?sch :val/kind "ref"] [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
             [?k :structure/of :lib.code/Kind] [?k :entity/name "StructureDb"]
             (in-module ?o ?cmn)
             [?e :structure/of :lib.code/Operation] [?e :val/extracted true] [?e :entity/name ?on]
             (in-module ?e ?kmn)
             [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)]
             [?pr :rel/from ?e] [?pr :rel/kind :performs] [?pr :rel/to ?eff] [?eff :val/name "throws"]]))

(defstructure EffectCorrespondence
  "Law-holder for the model↔code EFFECT correspondence — the effect dimension of the self-correspondence
   (sibling of `Realization`/`Fidelity`/`Encapsulation`/`Totality`). Every effect a modelled op's
   extracted twin TRANSITIVELY reaches (over `:calls` ∪ `:performs`) must appear in the op's authored
   `:performs`: design and extraction speak one effect language, to the depth of the call graph. Twin
   matched by name + corresponding module (`module-corresponds?`, as `Realization`).

   Direction: UNDER-declaration only (`reached ∖ declared`). Over-declaration (a declared effect the
   code doesn't reach) is NOT a violation — the classifier is necessarily incomplete (taxonomy +
   dynamic-dispatch gaps), so a phantom is a soft `effect-phantom` reading, never enforced. `:scope
   :global`; naturally vacuous on a model-only build (no extracted twin → nothing reached). The
   `reaches-effect` rule is purely self-recursive (passes `check-law-recursion!`, memoized within the
   query so it is cheap — ~0.2s of `check`); `in-module` resolves through the injected substrate rule.
   The `:rules` inline a copy of `lib.code/reaches-effect-rules` (a law's `:rules` is macro-time literal
   data — it cannot reference that var); keep the two in sync."
  (law "every effect a modelled op's twin transitively reaches is declared in the op's :performs"
    :scope :global
    :offenders '[?o]
    :rules '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
             [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]]
    :where '[[?o :structure/of :lib.code/Operation] (not [?o :val/extracted true]) [?o :entity/name ?on]
             (in-module ?o ?cmn)
             [?e :structure/of :lib.code/Operation] [?e :val/extracted true] [?e :entity/name ?on]
             (in-module ?e ?kmn)
             [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)]
             (reaches-effect ?e ?en)
             (not-join [?o ?en]
               [?dpr :rel/from ?o] [?dpr :rel/kind :performs] [?dpr :rel/to ?deff] [?deff :val/name ?en])]))

(defstructure LensCoverage
  "Law-holder for the LENS↔READER correspondence — the lens-analog of `Encapsulation`, at the read
   surface. fukan's bespoke probe readers (`probe-X`, the model-db→finding leaves in
   `projection/probes`) are the CODE realization of its declared `Lens` instruments: a reader is a
   focus run richly (annotated observations, composing `check`/correspondence). So every reader must
   be COVERED by a declared Lens of the same focus — you do not write a bespoke reader without first
   naming its focus as a `Lens` (intent), exactly as `Encapsulation` forbids an undeclared public op.
   The DUAL is deliberately NOT enforced: a Lens needs no reader — a reasoning lens like `purity` is
   run generically through its `:select` (`core.lens/evaluate-lens`), no bespoke leaf — so this guards
   reader→lens only.

   Match is on NAME (the `probe-` realization prefix; `reader-realizes-lens?`), no `:realizes`
   relation — mirroring `Realization`'s name-matching house style. `:scope :global` (offenders are the
   extracted readers, not LensCoverage). Vacuous on a model-only build (the leading `:val/extracted
   true` guard — no extracted readers, no offenders); the `Lens` instruments are authored design nodes
   coexisting on the merged graph, so a reader matches its lens across the altitude gap. The not-join
   binds `?rn` on entry (no free-variable blow-up); the Lens relation is non-empty in any real build
   (the instruments are always ingested)."
  (law "every extracted probe reader is covered by a declared Lens of the same focus"
    :scope :global
    :offenders '[?r]
    :where '[[?r :structure/of :lib.code/Operation] [?r :val/extracted true] [?r :entity/name ?rn]
             [(clojure.string/starts-with? ?rn "probe-")]
             (not-join [?rn]
               [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name ?ln]
               [(lib.code.correspondence/reader-realizes-lens? ?rn ?ln)])]))

(defn uncovered-readers
  "The LENS-COVERAGE worklist — extracted probe readers (`probe-X`) with no declared `Lens` of the
   same focus, as a set of reader names. Empty ⇔ every bespoke reader's focus is declared as a Lens
   instrument (the dual — a Lens with no reader — is allowed, see `LensCoverage`). Reads the single
   source of truth (the registered `LensCoverage` law, like `unfaithful-calls` reads `Fidelity`)."
  [db]
  (let [desc (-> (s/structure-by-tag ::LensCoverage) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn unrealized-delegates
  "The authored source Operations whose cross-module delegation is NOT realized by any actual call
   between the corresponding modules, as a set of op names. Empty ⇔ every intended module dependency
   is backed by real code. Reads the single source of truth (the registered CallRealization law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::CallRealization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn unrealized-dispatch
  "Authored cross-module delegations NOT realized op-level by the actual code — neither by a direct
   call nor by reaching the target THROUGH the code's call graph extended by modelled dispatch points
   (`:dispatches-to`). A set of authored source-op names; empty ⇔ every intended dependency is backed
   by a real (possibly dispatch-mediated, possibly multi-hop) call path.

   A QUERY, not a law (like `uncovered-calls`): it walks reachability in Clojure (efficient BFS),
   which a datalog law can't do within the kernel's law-timeout. It is nonetheless a genuine CONSUMER
   of `:dispatches-to` — a modelled dispatch point's fan-out is lifted onto the extracted call graph
   (by name + `module-corresponds?`), so removing a seam's `:dispatches-to` makes its consumers'
   delegations unreachable and surfaces them here. Asserted empty by the regression suite."
  [db]
  (let [ext-ops     (d/q '[:find ?e ?en ?km :in $ %
                           :where [?e :structure/of :lib.code/Operation] [?e :val/extracted true]
                                  [?e :entity/name ?en] (in-module ?e ?km)]
                         db rules/substrate-rules)
        ext-by-name (group-by second ext-ops)
        twin        (fn [on cm] (some (fn [[e _ km]] (when (module-corresponds? cm km) e))
                                      (get ext-by-name on)))
        calls       (d/q '[:find ?from ?to
                           :where [?c :rel/kind :calls] [?c :rel/from ?from] [?c :rel/to ?to]] db)
        disp        (d/q '[:find ?on1 ?cm1 ?on2 ?cm2 :in $ %
                           :where [?dr :rel/kind :dispatches-to] [?dr :rel/from ?a1] [?dr :rel/to ?a2]
                                  (not [?a1 :val/extracted true])
                                  [?a1 :entity/name ?on1] (in-module ?a1 ?cm1)
                                  [?a2 :entity/name ?on2] (in-module ?a2 ?cm2)]
                         db rules/substrate-rules)
        disp-edges  (keep (fn [[on1 cm1 on2 cm2]]
                            (let [e1 (twin on1 cm1) e2 (twin on2 cm2)]
                              (when (and e1 e2) [e1 e2])))
                          disp)
        adj         (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {}
                            (concat calls disp-edges))
        reaches?    (fn [start target]
                      (loop [stack [start] seen #{}]
                        (if-let [n (peek stack)]
                          (let [stack (pop stack)]
                            (cond
                              (= n target) true
                              (seen n)     (recur stack seen)
                              :else        (recur (into stack (get adj n)) (conj seen n))))
                          false)))
        delegations (d/q '[:find ?on1 ?cm1 ?on2 ?cm2 :in $ %
                           :where [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                                  (not [?o1 :val/extracted true])
                                  [?o1 :entity/name ?on1] (in-module ?o1 ?cm1)
                                  [?o2 :entity/name ?on2] (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]]
                         db rules/substrate-rules)]
    (->> delegations
         (keep (fn [[on1 cm1 on2 cm2]]
                 (let [e1 (twin on1 cm1) e2 (twin on2 cm2)]
                   (when (and e1 e2 (not (reaches? e1 e2))) on1))))
         set)))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law, like
   `uncovered-operations`): actual cross-module module-calls (over `:calls`) with no corresponding
   intended cross-module delegation (over `:delegates`, bridged by `module-corresponds?`), as a set
   of [caller-module callee-module] code-module-name pairs. The couplings the design has not yet
   declared. Computed by set-difference in Clojure (not `not-join`) to sidestep the empty-relation
   gotcha. A signal, not a violation: you do not model every call."
  [db]
  (let [intent (d/q '[:find ?cm1 ?cm2 :in $ %
                      :where [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                             (not [?o1 :val/extracted true])
                             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]]
                    db rules/substrate-rules)
        actual (d/q '[:find ?km1 ?km2 :in $ %
                      :where [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
                             [?e1 :val/extracted true] [?e2 :val/extracted true]
                             (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]]
                    db rules/substrate-rules)]
    (->> actual
         (remove (fn [[km1 km2]]
                   (some (fn [[cm1 cm2]]
                           (and (module-corresponds? cm1 km1) (module-corresponds? cm2 km2)))
                         intent)))
         set)))

(defn unfaithful-calls
  "The ENFORCED fidelity offenders — extracted caller Operations making an undeclared cross-module
   call between MODELLED faculties, as a set of op names. Empty ⇔ every modelled-faculty coupling in
   the code is declared as intent (so, with DAG-conformance green, the code conforms to the
   `:may-depend` DAG). The modelled-both-ends subset of `uncovered-calls`; reads the registered
   Fidelity law (the single source of truth)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Fidelity) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn drifted-operations
  "The AUTHORED operations in `db` with no same-named extracted operation, as a set of
   names. Empty ⇔ the model is fully realized in code. The focusable surface of the
   correspondence concern; reads the single source of truth (the registered law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Realization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn operation-sig
  "Render the AUTHORED Operation at `op-eid` to a malli function-schema
   `[:=> [:cat <each :in schema>] <:out schema, or :nil if none>]`, each `:in`/`:out`
   Schema rendered via the type dialect (`typing/render-type`). The `:in` targets are
   ordered/positional — rendered in `:rel/order` order — so the adherence comparison
   checks argument order and arity."
  [db op-eid]
  (let [ins  (->> (d/q '[:find ?ord ?to :in $ ?from
                         :where [?r :rel/from ?from] [?r :rel/kind :in] [?r :rel/to ?to] [?r :rel/order ?ord]]
                       db op-eid)
                  (sort-by first)
                  (mapv (fn [[_ to]] (typing/render-type db to))))
        out  (ffirst (d/q '[:find ?to :in $ ?from
                            :where [?r :rel/from ?from] [?r :rel/kind :out] [?r :rel/to ?to]]
                          db op-eid))]
    [:=> (into [:cat] ins) (if out (typing/render-type db out) :nil)]))

(defn type-drifted-operations
  "AUTHORED operations whose modelled type disagrees with the realizing function's declared
   `:malli/schema` — a type-drift signal (only checked where the code carries an annotation).
   Mirrors `uncovered-operations`' authored↔extracted pairing (same name, corresponding
   module via `module-corresponds?`), additionally requiring the extracted twin carries a
   `:val/sig`; collects the authored Operation's name when its rendered type does NOT adhere
   to the twin's realized signature."
  [db]
  (->> (d/q '[:find ?s ?sn ?o
              :where [?s :structure/of :lib.code/Operation] (not [?s :val/extracted true]) [?s :entity/name ?sn]
                     [?cr :rel/kind :child] [?cr :rel/from ?cm] [?cr :rel/to ?s]
                     [?cm :entity/name ?cmn]
                     [?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?sn]
                     [?o :val/sig _]
                     [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o]
                     [?km :entity/name ?kmn]
                     [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)]]
            db)
       (filter (fn [[s _ o]]
                 (not (typing/type-adheres?
                        (operation-sig db s)
                        (edn/read-string (:val/sig (d/entity db o)))))))
       (map second) set))

(defn uncovered-operations
  "The DUAL of drifted-operations — EXTRACTED operations in `db` with no authored operation
   of the same name in a corresponding module, as a set of names: code not covered by the
   model. A query, not a law — unmodelled code is a coverage signal, not a violation (you
   don't model every function).

   Module membership resolves through the `in-module` rule (`:exposes` ∪ `:owns` ∪ `:child`),
   matching the `Realization` law's own `in-module` — authored Operations attach to their Module
   via `:exposes` (the public surface), so a `:child`-only authored-twin lookup would miss every
   modelled op and report nearly all code as uncovered."
  [db]
  (->> (d/q '[:find ?on :in $ %
              :where [?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                     (in-module ?o ?kmn)
                     (not-join [?on ?kmn]
                       [?s :structure/of :lib.code/Operation] (not [?s :val/extracted true]) [?s :entity/name ?on]
                       (in-module ?s ?cmn)
                       [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)])]
            db rules/substrate-rules)
       (map first) set))

(defn uncovered-public-operations
  "The ENCAPSULATION worklist — the PUBLIC subset of `uncovered-operations`: extracted operations
   that are PUBLIC (not `:val/private true`) AND have no authored twin in a corresponding module.
   The principle (the dual at op-granularity of `uncovered-calls` at coupling-granularity): a
   function the model does not name should be an internal detail — so a PUBLIC uncovered function is
   an UNDECLARED PUBLIC SURFACE, demanding a decision (model it as intent, or make it private). The
   focusable surface of the encapsulation concern — reads the single source of truth (the registered
   `Encapsulation` law, like `unfaithful-calls` reads `Fidelity`). Empty ⇔ the whole public surface is
   covered or deliberately exempt; non-empty is the model-it-or-hide-it worklist.

   THREE categories are SETTLED and excluded: a `:val/private` function (an unmodelled internal is
   encapsulation working as intended); a `:val/export` function — public for MECHANISM (macro-emitted
   substrate, or a var reached only through dynamic dispatch: a datalog predicate, a `(syntax …)`/reader
   hook); and a `:val/test-support` function — public only for TEST-SUPPORT (test isolation/setup,
   never called from production). All three are intentionally public, not leaked internals; the model
   does not name them on purpose. The remaining worklist is the GENUINELY questionable surface: public,
   not exported, not test-support, and unmodelled — model it as intent, or make it private.

   Module membership resolves through `in-module` (see `uncovered-operations`)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Encapsulation) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn effect-drift
  "The effect-language correspondence reading: per MODELLED operation, the disagreement between its
   authored `:performs` intent and its extracted twin's TRANSITIVE effect profile (the truth, to the
   depth of the call graph; `lib.code/reached-effects`). Twin matched by name + corresponding module
   (`module-corresponds?`, as `Realization`). Returns `{op-name {:undeclared #{…} :phantom #{…}}}` for
   every op with a disagreement:
     :undeclared = reached ∖ declared — code reaches an effect the design never declared (HARD: a
                   detected effect is real, so this is a design blind spot — the enforced law direction).
     :phantom    = declared ∖ reached — the design declares an effect the code does not reach (SOFT:
                   a taxonomy gap, OR stale intent like a leftover `:throws`).
   A QUERY, not a law — the soft over-declaration (phantom) half is advisory (the classifier is
   incomplete), so it stays a reading; the hard under-declaration half is enforced by the
   `EffectCorrespondence` law (and surfaced by `undeclared-effects`)."
  [db]
  ;; Bind the twin (?e) in the SAME module-matched query the law uses, so the reading agrees with the
  ;; law by construction — a module-BLIND `[?e :entity/name ?on]` twin lookup would grab a same-named op
  ;; in the wrong module on a name collision, fabricating a drift the precise law never sees.
  (let [pairs    (d/q '[:find ?on ?o ?e :in $ %
                        :where [?o :structure/of :lib.code/Operation] (not [?o :val/extracted true]) [?o :entity/name ?on]
                               (in-module ?o ?cmn)
                               [?e :structure/of :lib.code/Operation] [?e :val/extracted true] [?e :entity/name ?on]
                               (in-module ?e ?kmn)
                               [(lib.code.correspondence/module-corresponds? ?cmn ?kmn)]]
                       db rules/substrate-rules)
        declared (fn [oeid] (set (d/q '[:find [?en ...] :in $ ?o :where [?pr :rel/from ?o] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]] db oeid)))]
    (reduce (fn [acc [on oeid teid]]
              (let [dec        (declared oeid)
                    rea        (code/reached-effects db teid)
                    undeclared (set/difference rea dec)
                    phantom    (set/difference dec rea)]
                (if (or (seq undeclared) (seq phantom))
                  (assoc acc on {:undeclared undeclared :phantom phantom})
                  acc)))
            {} pairs)))

(defn undeclared-effects
  "The EFFECT-CORRESPONDENCE offenders — modelled ops whose extracted twin TRANSITIVELY reaches an
   effect the op does not declare in its `:performs`, as a set of op names (the under-declaration
   direction). Empty ⇔ design declares every effect the code reaches, to the depth of the call graph:
   design and extraction speak one effect language. Over-declaration (phantom) is a soft `effect-drift`
   reading, never enforced — the classifier is incomplete (taxonomy + dynamic-dispatch gaps).

   The enforced invariant is the `EffectCorrespondence` law (it fires inside `check`). This reader is
   the FAST surface — derived from the `effect-drift` query rather than re-running the full `check`, so
   interactive/test use doesn't pay the whole-model check cost. Law and reader agree by construction
   (both = reached ∖ declared, transitively)."
  [db]
  (set (for [[on m] (effect-drift db) :when (seq (:undeclared m))] on)))

(defn totality-violations
  "The ENFORCED TOTALITY offenders — trusted-core READER operations whose realizing code is PARTIAL,
   as a set of op names. A reader is a modelled Operation whose `:in` signature references the trust
   artifact `StructureDb` (the Model); it operates on the trusted graph, so parse-don't-validate says
   it must be TOTAL. An entry is such a reader whose extracted twin throws. Empty ⇔ the modelled
   trusted core is total — the property the `Totality` law asserts. Reads the single source of truth
   (the registered `Totality` law, like `unfaithful-calls` reads `Fidelity`)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Totality) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))
