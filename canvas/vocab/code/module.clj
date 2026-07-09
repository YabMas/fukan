(ns canvas.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace), its derived module-dependency reading,
   AND THE CORRESPONDENCE EXTENSION HOME: the `module-corresponds?` name bridge, the `op-twin` alias,
   and the external `(correspond Operation …)`/`(correspond Module …)` hooks that contribute the
   fact-side (`:calls`/`:sig`/… slots), the twin, and every model↔code demand to Operation/Module
   FROM OUTSIDE (inverted dependency — the concepts' own defstructures mention no correspondence).
   The call-graph demand readers live in `canvas.principles.layered-architecture`."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            [fukan.canvas.core.substrate :as sub]
            [canvas.vocab.code.operation :as operation :refer [Operation]]
            [canvas.vocab.code.kind :refer [Kind]]
            [canvas.vocab.code.contract :refer [Contract]]))

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

   PURE IDENTITY — Module is the ROOT of the correspondence twin ladder, but that (the bridge, the
   `:extracted` fact-slot) hooks in from OUTSIDE via `(correspond Module …)` below, not here."
  {:exposes [:* {:contains true} Operation]   ; the public API surface — Operations callers depend on
   :owns    [:* {:contains true} Kind]        ; data-shapes that cross the boundary (other modules adopt by name)
   :offers  [:* {:contains true} Contract]    ; contracts it owns for OTHERS to implement (plug-points / SPIs)
   :child   [:* {:contains true} Any]})       ; internal members + grain no other module consumes

;; ── the correspondence EXTENSION: hooks Operation + Module from OUTSIDE (inverted dependency) ──
;; The concepts' defstructures mention no correspondence; these declarations contribute the fact-side
;; slots, the twin, and every model↔code demand to their tags — faithful to the old inline (corresponds …).

(s/correspond Module :by-name (bridge module-corresponds?)
  {:extracted [:? :boolean]})        ; provenance: true ⇒ from code extraction (stamped by the build)

(s/correspond Operation :by-name
  {:calls     [:* {:transitive true} Operation]  ; the ACTUAL call graph (extraction's actuals); :transitive ⇒ calls+
   :sig       [:? :string]           ; the code's REALIZED malli signature (stamped from :malli/schema)
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]       ; intentionally public for TEST-SUPPORT (^:test-support)
   :extracted [:? :boolean]}         ; provenance: true ⇒ from code
  ;; ex-Realization — vacuity-guarded: ∃ extracted Operation
  (realized {:desc "every authored operation is realized by an extracted operation of the same name in the corresponding module"})
  ;; ex-TypeCoverage — public modelled op's realizing code carries a :malli/schema
  (realized {:key :type-coverage
             :desc "every public modelled operation's realizing code carries a type signature (:malli/schema)"
             :when '[(exposed ?x)]
             :require '[[?t :val/sig ?_s]]})
  ;; ex-Encapsulation — the exemption flags are VOCAB's (the kernel never names them)
  (covered  {:desc "every public extracted operation is covered by the model or deliberately exempt"
             :unless '[[?x :val/private true] [?x :val/export true] [?x :val/test-support true]]})
  ;; GATED signature adherence (exact match): wherever the twin declares a :val/sig, the modelled
  ;; signature must EXACTLY adhere. A missing sig is type-coverage's offence — hence the :when guard.
  (agrees   {:key :adheres :by :signature
             :desc "every modelled operation's realizing code signature exactly adheres to its modelled type"
             :when '[[?t :val/sig ?_s]]})
  ;; relation demands ABOUT Operation's own identity relations (:delegates / :performs):
  (delegates {:realized-by :calls :faithful true :altitude :container})  ; cross-module :delegates realized by a :calls path (+ faithful reverse)
  (performs  {:covered-from [:calls* :performs]}))                       ; every effect the twin REACHES over :calls*·:performs is declared

;; the `:signature` comparator the `(agrees {:by :signature})` demand runs per twin pair: the design
;; op's rendered signature must EXACTLY adhere to the extracted twin's realized `:val/sig`. Owns both
;; extraction sides + the dialect adherence call, so the kernel stays type-agnostic.
(s/register-comparator! :signature
  (fn [db a b]
    (typing/type-adheres? (operation/operation-sig db a)
                          (edn/read-string (:val/sig (cq/entity db b))))))

;; ── derived module-dependency relations ───────────────────────────────
;; `module-owns` / `module-depends` are DEFRELATIONS — injected into every law and query by
;; `check`/`vocab-rules`, so the laws that need them (Subsystem `:may-depend` conformance,
;; ModuleArchitecture acyclicity) and the reader below reference them BY NAME instead of each
;; re-inlining a copy. The compiler emits only the rules a query actually reaches, so laws that
;; never mention module-depends pay nothing. `module-owns` is Module ownership expressed as the
;; generic `contains` union (the `:contains` handler) restricted to a Module container — Module's
;; :exposes/:owns/:child are all {:contains true}.

(s/defrelation :module-owns
  "Module ?m owns ?x — the `contains` union (:exposes/:owns/:child) restricted to a Module container."
  '[?m ?x]
  '[[?m :structure/of :canvas.vocab.code.module/Module] (contains ?m ?x)])

(s/defrelation :module-depends
  "the COMPLETE module→module dependency graph: a call dependency (?m owns an op that :delegates to
   an op ?n owns) UNIONed with data-adoption (?m owns an op whose :in/:out ref-Schema :names a Kind
   ?n owns). The reader `module-dependencies` and the layering laws read this by name."
  '[?m ?n]
  '[(module-owns ?m ?op)
    (or-join [?op ?n]
      (and (delegates ?op ?op2) (module-owns ?n ?op2))
      (and (in ?op ?sch)  (names-kind ?sch ?k) (module-owns ?n ?k))
      (and (out ?op ?sch) (names-kind ?sch ?k) (module-owns ?n ?k)))
    [(not= ?m ?n)]])

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set of
   [caller-name callee-name] pairs. A pure read over the reified code graph."
  [db]
  (set (cq/q '[:find ?mn ?nn :in $
               :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]
             db)))

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
;; laws/readers that reference op-twin by name; the actual rules are generated by the declaration registry (`terms-of`).
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
   the kernel now allows; the query negates it under stratified negation. The injected rules
   (`Operation`/`design`/`fact`/`in-module`) are ambient in any `cq/q` — only these on-graph
   reachability rules need be supplied. `in-module` binds Kinds too, so op-ness is guarded here
   explicitly with `(Operation …)` (the design/fact stratum rules are kind-agnostic)."
  '[[(op-ext-twin ?a ?e)
     (Operation ?a) (design ?a) [?a :entity/name ?n] (in-module ?a ?am)
     (Operation ?e) (fact ?e) [?e :entity/name ?n] (in-module ?e ?em)
     [(canvas.vocab.code.module/module-corresponds? ?am ?em)]]
    [(ext-edge ?from ?to) (calls ?from ?to)]
    [(ext-edge ?e1 ?e2)
     (dispatches-to ?a1 ?a2)
     (op-ext-twin ?a1 ?e1) (op-ext-twin ?a2 ?e2)]
    [(ext-reaches ?a ?b) (ext-edge ?a ?b)]
    [(ext-reaches ?a ?b) (ext-edge ?a ?mid) (ext-reaches ?mid ?b)]])

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
               :where (delegates ?o1 ?o2)
                      (design ?o1)
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
