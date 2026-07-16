(ns fukan.common.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace), its correspondence to a code namespace, and
   the derived module-dependency graph. Holds the `module-corresponds?` name bridge (canvas-module ↔
   code-ns), Module's own `(correspond Module …)` (the `:extracted` fact-slot + the bridged twin root),
   and the `module-owns`/`module-depends` derivations that Subsystem's architecture laws read by name.
   Operation's own correspondence (the fact-side slots, `op-twin`, the call-realization demands +
   readers) lives with the element itself in `fukan.common.vocab.code.operation`; this file only
   supplies the module↔code-ns bridge those readers reach via query-time port."
  (:require [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.plug-point :refer [PlugPoint]]))

;; ── the cross-element correspondence bridge ───────────────────────────────────
;; MUST be defined before Module's defstructure: the (corresponds …) body-form
;; resolves the bridge symbol at macro-expansion time.

(defn ^:export module-corresponds?
  "True when code namespace `km` realizes canvas module `cm`. Deterministic, separator-agnostic:
   split both on `[-.]` into segments; the canvas name's segments must be a SUFFIX of the code
   namespace's. So `infra-model` ← `fukan.infra.model`, `canvas-source` ←
   `fukan.canvas.ingestion.canvas-source`, `core-structure` ← `fukan.canvas.core.structure`.
   (Canvas module names are hyphenated and equal their vars; the code path is dotted — this rule
   bridges the two without the model authoring a second name string.)"
  [cm km]
  (let [segs #(str/split % #"[-.]")
        c    (segs cm)]
    (= c (take-last (count c) (segs km)))))

;; The module-correspondence Cozo port — registered into the query compiler's predicate-port SPI so the
;; GENERIC kernel compiler need not name `Module` or re-implement module-corresponds? in CozoScript.
(cq/register-predicate-port!
 'fukan.common.vocab.code.module/module-corresponds?
 (fn [[cm km]] [(str "r_module_corresponds[" cm ", " km "]") #{"r_module_corresponds"}])
 {"r_canvas_module" {:lines ["r_canvas_module[cm] := triple[m, 'structure/of', 'fukan.common.vocab.code.module/Module'], not triple[m, 'val/extracted', true], triple[m, 'entity/name', cm]"]
                     :refs #{}}
  "r_code_module"   {:lines ["r_code_module[km] := triple[m, 'structure/of', 'fukan.common.vocab.code.module/Module'], triple[m, 'val/extracted', true], triple[m, 'entity/name', km]"]
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
  {:exposes   [:* {:contains true} Operation]  ; the public API surface — Operations callers depend on
   :owns      [:* {:contains true} Kind]       ; data-shapes that cross the boundary (other modules adopt by name)
   :offers    [:* {:contains true} PlugPoint]  ; plug-points it OWNS for others to satisfy (SPIs / dependency-inversion points)
   :satisfies [:* PlugPoint]                   ; plug-points it SATISFIES (owned elsewhere) — the inverted edge; NOT containment
   :child     [:* {:contains true} Any]})      ; internal members + grain no other module consumes

;; ── Module's own correspondence: the bridged twin root ────────────────────────
;; Module is the ROOT of the correspondence twin ladder — `(bridge module-corresponds?)` pairs a
;; canvas Module with its extracted code twin by name, and every Operation twin nests WITHIN a twinned
;; Module pair. Declared from outside the defstructure so the identity above stays pure.

(s/correspond Module :by-name (bridge module-corresponds?)
  {:extracted [:? :boolean]})        ; provenance: true ⇒ from code extraction (stamped by the build)

;; ── derived module-dependency relations ───────────────────────────────
;; `module-owns` / `module-depends` are DEFRELATIONS — injected into every law and query by
;; `check`/`vocab-rules`, so the laws that need them (Subsystem's `:may-depend` conformance + its
;; rehomed module-graph acyclicity) and the reader below reference them BY NAME instead of each
;; re-inlining a copy. The compiler emits only the rules a query actually reaches, so laws that
;; never mention module-depends pay nothing. `module-owns` is Module ownership expressed as the
;; generic `contains` union (the `:contains` handler) restricted to a Module container — Module's
;; :exposes/:owns/:child are all {:contains true}.

(s/defrelation :module-owns
  "Module ?m owns ?x — the `contains` union (:exposes/:owns/:child) restricted to a Module container."
  '[?m ?x]
  '[[?m :structure/of :fukan.common.vocab.code.module/Module] (contains ?m ?x)])

(s/defrelation :module-depends
  "the COMPLETE module→module dependency graph: a call dependency (?m owns an op that :delegates to
   an op ?n owns) UNIONed with data-adoption (?m owns an op whose :in/:out ref-Schema references a Kind
   ?n owns, by name). The reader `module-dependencies` and the layering laws read this by name."
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
