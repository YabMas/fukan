(ns canvas.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace), its derived module-dependency reading,
   AND the CROSS-ELEMENT correspondence: the `module-corresponds?` name bridge + the `op-twin`
   alias of the kernel twin (the (corresponds …) declarations carry the pairing).
   The call-graph correspondence laws are GENERATED from Operation's `:delegates` slot options;
   their readers live in `canvas.principles.layered-architecture`."
  (:require [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.rules :as rules]
            [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.kind :refer [Kind]]))

;; ── the cross-element correspondence bridge ───────────────────────────────────
;; MUST be defined before Module's defstructure: the (corresponds …) body-form
;; resolves the bridge symbol at macro-expansion time.

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

;; The module-correspondence Cozo port — registered into the query compiler's predicate-port SPI so the
;; GENERIC kernel compiler need not name `Module` or re-implement module-corresponds? in CozoScript.
(cq/register-predicate-port!
 'canvas.vocab.code.module/module-corresponds?
 (fn [[cm km]] [(str "r_module_corresponds[" cm ", " km "]") #{"r_module_corresponds"}])
 {"r_canvas_module" {:lines ["r_canvas_module[cm] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], not triple[m, 'val/extracted', true], triple[m, 'entity/name', cm]"]
                     :refs #{}}
  "r_code_module"   {:lines ["r_code_module[km] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], triple[m, 'val/extracted', true], triple[m, 'entity/name', km]"]
                     :refs #{}}
  "r_module_corresponds" {:lines ["r_module_corresponds[cm, km] := r_canvas_module[cm], r_code_module[km], cmn = regex_replace_all(cm, '-', '.'), kmn = regex_replace_all(km, '-', '.'), or(kmn == cmn, ends_with(kmn, concat('.', cmn)))"]
                          :refs #{"r_canvas_module" "r_code_module"}}})

(defstructure Module
  "A code module — one cohesion boundary (a namespace). Like a `Grouping` it collects members
   (`:child`), but it ALSO carries code semantics: an explicit API surface (`:exposes`) and the
   data-shapes it is the source of truth for (`:owns`). Conceptually a Module IS-A Grouping.

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the data-shapes
   that CROSS THE BOUNDARY — Kinds other modules ADOPT by name (and don't redefine); `:child` is the
   internal membership / ownership backbone (`in-module` resolves over `:exposes`/`:owns`/`:child`),
   the home for grain a module is source-of-truth-for but no one else consumes. The discriminant is
   adoption: a data-shape no other module names is internal grain (`:child`), not a boundary (`:owns`).

   Corresponds as the ROOT of the twin ladder: a design Module twins an extracted one via the
   `module-corresponds?` name bridge (the `(corresponds …)` declaration — nested kinds twin within
   these pairs)."
  (corresponds :by-name (bridge module-corresponds?))
  {:exposes [:* {:contains true} Operation]   ; the public API surface — Operations callers depend on
   :owns    [:* {:contains true} Kind]        ; data-shapes that cross the boundary (other modules adopt by name)
   :child   [:* {:contains true} Any]         ; internal members + grain no other module consumes
   :extracted [:? :boolean]})        ; provenance: true ⇒ from code extraction; absent/false ⇒ authored (symmetric with Operation)

;; ── derived module-dependency readings ────────────────────────────────

(def module-depends-rules
  "Datalog over the reified code graph: `module-depends` is the COMPLETE module→module dependency
   graph — call dependencies (an owned Operation `:delegates` to another module's Operation) UNIONed
   with data-adoption (an owned Operation's `:in`/`:out` is a ref-`Schema` whose `:names` edge reaches
   a `Kind` another module owns). `module-owns` is ownership via `:exposes`/`:owns`/`:child`.
   NB: `ModuleArchitecture` (in `canvas.principles.layered-architecture`) and `Subsystem`'s
   `:may-depend` conformance law (in `canvas.vocab.code.subsystem`) each INLINE a copy of these
   rules (a law's `:rules` is macro-time literal data — it cannot reference this var); keep all
   copies in sync."
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

;; ── op pairing ───────────────────────────────────────────────────────────────

;; The module-membership CozoScript fragment (op→owning-module-name over child/exposes/owns) — it names
;; code-vocab relations, so it lives in VOCAB, prepended (after the generic `rules/eav`) by the cozo
;; consumers that need raw-CozoScript membership: `latent-boundaries` and the extractor's :calls grounding.
(def in-module-cozo
  "
in_module[e, mname] := relkind[r, 'child'],   relfrom[r, m], relto[r, e], ename[m, mname]
in_module[e, mname] := relkind[r, 'exposes'], relfrom[r, m], relto[r, e], ename[m, mname]
in_module[e, mname] := relkind[r, 'owns'],    relfrom[r, m], relto[r, e], ename[m, mname]
")

;; op-twin — an alias of the generic kernel `twin` restricted to the Operation kind.
;; The pairing semantics live in the (corresponds …) declarations: Module = the bridged root,
;; Operation = nested by name within twinned Modules. This alias exists for the correspondence
;; laws/readers that reference op-twin by name; the actual rules are generated by `derive-rules`.
(s/defrelation :op-twin
  "an authored Operation ?a and its extracted code twin ?b — the generic kernel `twin`
   restricted to Operation kind. The pairing semantics live in the (corresponds …)
   declarations (Module = the bridged root, Operation = nested by name); this alias exists
   for the correspondence laws/readers that reference op-twin."
  '[?a ?b]
  '[[?a :structure/of :canvas.vocab.code.operation/Operation] (twin ?a ?b)])

(def ^:private unrealized-dispatch-rules
  "Reachability over the EXTRACTED graph, on-graph. `op-ext-twin` pairs an authored op with its
   extracted code twin (same name + `module-corresponds?` modules). `ext-edge` is the call graph
   extended by modelled dispatch: a `:calls` edge, OR a `:dispatches-to` edge lifted onto the twins
   of its authored endpoints. `ext-reaches` is its transitive closure — a rule-calls-rule recursion
   the kernel now allows; the query negates it under stratified negation."
  (into rules/substrate-rules
        '[[(op-ext-twin ?a ?e)
           ;; the authored-op guard is INLINED here (not the `authored` defrelation): this ruleset is
           ;; `(into substrate-rules …)`, which carries only the fixed substrate rules — defrelations
           ;; are injected solely by `check`/`vocab-rules`, not into a hand-built `cq/q` ruleset.
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
   InstanceValues (`op-ivs`) via `:child`. Provenance (`:val/extracted`) is stamped by the BUILD
   at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ivs]
  (sub/->InstanceValue ::Module (str mname) nil nil
                       [{:rk :child :card :many :targets (vec op-ivs)}] false))
