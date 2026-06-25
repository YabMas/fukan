(ns canvas.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace), its derived module-dependency reading,
   AND the CROSS-ELEMENT correspondence: the `module-corresponds?` name bridge + the `op-twin`
   pairing built on it (used by the operation/effect/fukan laws via datalog injection), plus
   Module's own CallRealization/Fidelity laws and their readers."
  (:require [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.rules :as rules]
            [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.kind :refer [Kind]]))

(defstructure Module
  "A code module — one cohesion boundary (a namespace). Like a `Grouping` it collects members
   (`:child`), but it ALSO carries code semantics: an explicit API surface (`:exposes`) and the
   data-shapes it is the source of truth for (`:owns`). Conceptually a Module IS-A Grouping.

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the data-shapes
   that CROSS THE BOUNDARY — Kinds other modules ADOPT by name (and don't redefine); `:child` is the
   internal membership / ownership backbone (`in-module` resolves over `:exposes`/`:owns`/`:child`),
   the home for grain a module is source-of-truth-for but no one else consumes. The discriminant is
   adoption: a data-shape no other module names is internal grain (`:child`), not a boundary (`:owns`)."
  {:exposes [:* Operation]           ; the public API surface — Operations callers depend on
   :owns    [:* Kind]                ; data-shapes that cross the boundary (other modules adopt by name)
   :child   [:* Any]                 ; internal members + grain no other module consumes
   :extracted [:? :boolean]})        ; provenance: true ⇒ from code extraction; absent/false ⇒ authored (symmetric with Operation)

;; ── derived module-dependency readings ────────────────────────────────

(def module-depends-rules
  "Datalog over the reified code graph: `module-depends` is the COMPLETE module→module dependency
   graph — call dependencies (an owned Operation `:delegates` to another module's Operation) UNIONed
   with data-adoption (an owned Operation's `:in`/`:out` is a ref-`Schema` whose `:names` edge reaches
   a `Kind` another module owns). `module-owns` is ownership via `:exposes`/`:owns`/`:child`.
   NB: the `subsystem` no-mutual-dependency law INLINES an identical copy of these rules (a law's
   `:rules` is macro-time literal data — it cannot reference this var); keep the two copies in sync."
  '[[(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?x]]
    [(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :owns]    [?r :rel/to ?x]]
    [(module-owns ?m ?x) [?m :structure/of :canvas.vocab.code.module/Module] [?r :rel/from ?m] [?r :rel/kind :child]   [?r :rel/to ?x]]
    [(module-depends ?m ?n)                                  ; call dependency
     (module-owns ?m ?op1) [?dr :rel/from ?op1] [?dr :rel/kind :delegates] [?dr :rel/to ?op2]
     (module-owns ?n ?op2) [(not= ?m ?n)]]
    [(module-depends ?m ?n)                                  ; data-adoption dependency
     (module-owns ?m ?op)
     (or-join [?op ?sch]
       (and [?ir :rel/from ?op] [?ir :rel/kind :in]  [?ir :rel/to ?sch])
       (and [?o2 :rel/from ?op] [?o2 :rel/kind :out] [?o2 :rel/to ?sch]))
     [?sch :val/kind "ref"]
     [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
     (module-owns ?n ?k) [(not= ?m ?n)]]])

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set of
   [caller-name callee-name] pairs. A pure read over the reified code graph."
  [db]
  (set (cq/q '[:find ?mn ?nn :in $ %
               :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]
             db module-depends-rules)))

;; ── the cross-element correspondence bridge + op pairing ──────────────────────

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

;; op-twin — the model↔code Operation pairing, defined ONCE as a derived relation and injected
;; into every correspondence law/query at domain altitude (by `check`, like the vocab-derived rules).
;; An authored op ?a is twinned with an extracted op ?b of the same NAME in a CORRESPONDING module
;; (`module-corresponds?`). This is the single home of the matching the operation/effect/fukan laws
;; reference; it lives here because it is built on the Module bridge (the cross-element correspondence).
(s/defrelation :op-twin
  "an authored Operation ?a and its extracted code twin ?b — same name, corresponding module"
  '[?a ?b]
  '[[?a :structure/of :canvas.vocab.code.operation/Operation] (not [?a :val/extracted true]) [?a :entity/name ?n]
    (in-module ?a ?cm)
    [?b :structure/of :canvas.vocab.code.operation/Operation] [?b :val/extracted true] [?b :entity/name ?n]
    (in-module ?b ?km)
    [(canvas.vocab.code.module/module-corresponds? ?cm ?km)]])

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
   (self-contained, the `subsystem` convention)."
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
   `intended` rule inlines `in-module` (the `subsystem` convention)."
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
  [db]
  (let [desc (-> (s/structure-by-tag ::CallRealization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law, like
   `uncovered-operations`): actual cross-module module-calls (over `:calls`) with no corresponding
   intended cross-module delegation (over `:delegates`, bridged by `module-corresponds?`), as a set
   of [caller-module callee-module] code-module-name pairs. The couplings the design has not yet
   declared. Computed by set-difference in Clojure (not `not-join`) to sidestep the empty-relation
   gotcha. A signal, not a violation: you do not model every call."
  [db]
  (let [intent (cq/q '[:find ?cm1 ?cm2 :in $ %
                      :where [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                             (not [?o1 :val/extracted true])
                             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]]
                    db rules/substrate-rules)
        actual (cq/q '[:find ?km1 ?km2 :in $ %
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
         (map #(:entity/name (cq/entity db %)))
         set)))

(def ^:private unrealized-dispatch-rules
  "Reachability over the EXTRACTED graph, on-graph. `op-ext-twin` pairs an authored op with its
   extracted code twin (same name + `module-corresponds?` modules). `ext-edge` is the call graph
   extended by modelled dispatch: a `:calls` edge, OR a `:dispatches-to` edge lifted onto the twins
   of its authored endpoints. `ext-reaches` is its transitive closure — a rule-calls-rule recursion
   the kernel now allows; the query negates it under stratified negation."
  (into rules/substrate-rules
        '[[(op-ext-twin ?a ?e)
           [?a :structure/of :canvas.vocab.code.operation/Operation] (not [?a :val/extracted true])
           [?a :entity/name ?n] (in-module ?a ?am)
           [?e :structure/of :canvas.vocab.code.operation/Operation] [?e :val/extracted true]
           [?e :entity/name ?n] (in-module ?e ?em)
           [(canvas.vocab.code.module/module-corresponds? ?am ?em)]]
          [(ext-edge ?from ?to) [?c :rel/kind :calls] [?c :rel/from ?from] [?c :rel/to ?to]]
          [(ext-edge ?e1 ?e2)
           [?dr :rel/kind :dispatches-to] [?dr :rel/from ?a1] [?dr :rel/to ?a2]
           (op-ext-twin ?a1 ?e1) (op-ext-twin ?a2 ?e2)]
          [(ext-reaches ?a ?b) (ext-edge ?a ?b)]
          [(ext-reaches ?a ?b) (ext-edge ?a ?mid) (ext-reaches ?mid ?b)]]))

(defn unrealized-dispatch
  "Authored cross-module delegations NOT realized op-level by the actual code — neither by a direct
   call nor by reaching the target THROUGH the code's call graph extended by modelled dispatch points
   (`:dispatches-to`). A set of authored source-op names; empty ⇔ every intended dependency is backed
   by a real (possibly dispatch-mediated, possibly multi-hop) call path.

   A QUERY, not a law (like `uncovered-calls`): reachability is on-graph datalog (`ext-reaches`, the
   transitive closure of `:calls` ∪ lifted `:dispatches-to`, negated under stratification) — no Clojure
   walk. It is nonetheless a genuine CONSUMER of `:dispatches-to`: a modelled dispatch point's fan-out is
   lifted onto the extracted call graph (by name + `module-corresponds?`), so removing a seam's
   `:dispatches-to` makes its consumers' delegations unreachable and surfaces them here. An offender's
   delegation has BOTH endpoints twinned in code yet no realized path between them; a delegation whose
   source or target has no extracted twin is out of scope. Asserted empty by the regression suite."
  [db]
  (->> (cq/q '[:find ?on1 :in $ %
               :where [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                      (not [?o1 :val/extracted true])
                      [?o1 :entity/name ?on1] (in-module ?o1 ?cm1)
                      (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
                      (op-ext-twin ?o1 ?e1) (op-ext-twin ?o2 ?e2)
                      (not (ext-reaches ?e1 ?e2))]
             db unrealized-dispatch-rules)
       (map first) set))

;; ── Clojure extraction (ns → Module) ─────────────────────────────────────────

(defn extract-module
  "Build an extracted Module InstanceValue named `mname` owning the given extracted Operation
   InstanceValues (`op-ivs`) via `:child`, stamped `:val/extracted true` (provenance)."
  [mname op-ivs]
  (sub/->InstanceValue ::Module (str mname) nil {:val/extracted true}
                       [{:rk :child :card :many :targets (vec op-ivs)}] false))
