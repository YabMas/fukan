(ns lib.code
  "Shared-lib vocabulary — the grammar for describing CODE: `Kind` (atomic type),
   `Effect`, `Operation` (a unit of computation) and `Module` (a code boundary).
   These are standard code-structures, NOT unique to fukan — so they live in the
   reusable `lib/` stdlib, not in fukan's own canvas vocab. A consumer (a self-model
   or a demo) opts in by requiring this namespace; the extractor `extract` produces
   instances of these by tag, the model authors instances of them, and they meet at
   merge.

   Opt-in (required, not auto-discovered like `canvas/**`); ingests no instances."
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Connected]]
            [lib.type.malli :as lib.type.malli :refer [Schema]]))

;; ── data types ───────────────────────────────────────────────────────────────

(defn ^:export shape->slots
  "Kind's authoring syntax (the `(syntax …)` hook, map → map): a positional malli body is
   wrapped into the `:shape` slot; a map body passes through (the named-slot form)."
  [b] (if (map? b) b {:shape b}))

(defstructure Kind
  "A named data-shape — the nominal handle for a value type a Module owns. Its body IS
   its shape: a `Schema` (the pluggable typing) authored positionally — a scalar
   (`:int`), a record (`[:map …]`), a union (`[:or …]`), a collection (`[:vector …]` /
   `[:map-of …]`), or an arrow (`[:=> …]`). A Kind with NO body is an opaque external
   (a datascript db, a filesystem reality) — honestly shapeless. Owned by at most one
   Module (`:owns` — others adopt it by name, they don't redefine it)."
  {:shape [:? Schema]}                          ; its shape, when it has one (authored positionally)
  (syntax shape->slots)                         ; positional malli body → the :shape slot
  (law "a Kind is owned by at most one Module"
    (at-most-one :owns)))

;; ── computation ──────────────────────────────────────────────────────────────

(defn ^:export read-effect
  "Expand an effect literal — a keyword like `:io` — into Effect clauses, so
   effects author as `:performs [:io :require]`."
  [kw]
  [(list 'name (name kw))])

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Operation that performs it."
  {:name :string}
  (reader read-effect))

(defn ^:export signature->slots
  "Operation's authoring syntax (the `(syntax …)` hook, map → map): a `:signature`
   pseudo-key is rewritten into the ordered+labelled `:in` vector and the `:out` entry.
   The value is a malli function schema `[:=> INPUT OUTPUT]`; INPUT is `[:catn [:name Type] …]`
   (named params → ordered + labelled `[name Type]` pairs) or `[:cat]` (nullary). A
   `[:cat Type …]` (positional, unnamed) is REJECTED — name your parameters. The Types are
   ordinary malli, expanded by the Schema reader (a bare symbol is a var-ref to a Kind).
   Lives in the vocab — malli never touches core."
  [m]
  (if-not (contains? m :signature)
    m
    (let [form (:signature m)]
      (when-not (and (vector? form) (= :=> (first form)) (= 3 (count form)))
        (throw (ex-info (str "signature must be a malli function schema [:=> INPUT OUTPUT]: " (pr-str form)) {:form form})))
      (let [[_ input output] form]
        ;; keep this guard ahead of catn->pairs: it reports against the whole [:=> …] form
        (when-not (vector? input)
          (throw (ex-info (str "signature input must be [:catn …] or [:cat]: " (pr-str input)) {:form form})))
        (let [in (lib.type.malli/catn->pairs input)]
          (cond-> (-> m (dissoc :signature) (assoc :out output))
            (seq in) (assoc :in in)))))))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (`:extracted true`, stamped by the plug-point — name + privacy, and
   actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Module)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with a malli signature: `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out] :delegates […]})`
   — the `(syntax signature->slots)` hook rewrites `:signature` to the `:in`/`:out` entries.
   (Replaces the old `Stage` and the extractor's private `Operation` — one vocab, owned here,
   the extractor produces into it by tag.)

   A boundary sketch authors `:delegates` (the cross-module surfaces it relies on — designed
   dependencies) and `:guidance` (implementer-directed intent); it does NOT author `:calls` —
   internal wiring is extraction's job. `:calls` is therefore the EXTRACTED actual-call graph."
  (includes Connected)
  (syntax signature->slots)          ; {:signature [:=> [:catn …] Out]} authoring entry (vocab-owned)
  {:in        [:* Schema]            ; input shapes — positional, each labelled with its param name
   :out       [:? Schema]            ; output schema (authored ops declare one; extracted may not)
   :performs  [:* Effect]            ; side effects
   :delegates [:* Operation]         ; cross-boundary dependencies it relies on (authored, designed)
   :dispatches-to [:* Operation]     ; indirection: handler Operations this dispatch point routes to (authored intent — a design statement, not an extracted fact)
   :guidance  [:? :string]           ; implementer-directed design intent (algorithm/perf/library) — rendered by the projection
   :calls     [:* Operation]         ; the ACTUAL call graph (extraction's actuals; not authored)
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (macro emission / dynamic dispatch); settled, not a coverage gap (from ^:export)
   :test-support [:? :boolean]       ; intentionally public for TEST-SUPPORT (test isolation / setup, never called from production); settled (from ^:test-support)
   :extracted [:? :boolean]          ; provenance: true ⇒ from code; absent/false ⇒ authored
   ;; the code's REALIZED malli signature (a pr-str'd `[:=> …]` form), stamped by extraction
   ;; from `:malli/schema` metadata; authored Operations leave it empty and use :in/:out.
   :sig       [:? :string]})

;; ── code module (boundary + ownership) ───────────────────────────────────────

(defstructure Module
  "A code module — one cohesion boundary (a namespace). Like a `Grouping` it collects members
   (`:child`), but it ALSO carries code semantics: an explicit API surface (`:exposes`) and the
   data-shapes it is the source of truth for (`:owns`). Conceptually a Module IS-A Grouping;
   making that an explicit `(includes Grouping)` waits until slot type-checks honor
   `includes`-satisfaction (so `FacultyRealization.realizer` can tighten from `Any` to
   `Grouping`) — deferred theory-composition work. (A Module is NOT a `Subsystem`: that level —
   a cluster of modules realizing a capability — is reserved and undefined for now; today every
   code namespace is a Module.)

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the data-shapes
   that CROSS THE BOUNDARY — Kinds other modules ADOPT by name (and don't redefine); `:child` is the
   internal membership / ownership backbone (`in-module` resolves over `:exposes`/`:owns`/`:child`),
   the home for grain a module is source-of-truth-for but no one else consumes. The discriminant is
   adoption: a data-shape no other module names is internal grain (`:child`), not a boundary (`:owns`)."
  {:exposes [:* Operation]           ; the public API surface — Operations callers depend on
   :owns    [:* Kind]                ; data-shapes that cross the boundary (other modules adopt by name)
   :child   [:* Any]                 ; internal members + grain no other module consumes
   :extracted [:? :boolean]})        ; provenance: true ⇒ from code extraction; absent/false ⇒ authored (symmetric with Operation)

;; ── subsystem (the rung above Module: a capability cluster) ───────────────────

(defstructure Subsystem
  "A cluster of Modules realizing a capability — the rung above Module in the grouping ladder
   (Grouping ⊂ Module ⊂ Subsystem). Owns its Modules (`:child`, ownership-on-owner) and DECLARES the
   subsystems it is allowed to depend on (`:may-depend` — the intended architecture DAG, as declared
   intent). `:may-depend` is a self-reference,
   exactly like `Operation :delegates` — the assembler resolves the var-refs."
  {:child      [:* Module]        ; the Modules this subsystem clusters
   :may-depend [:* Subsystem]})   ; the subsystems it is allowed to depend on (declared intent)

;; ── derived module-dependency readings ────────────────────────────────

(def module-depends-rules
  "Datalog over the reified code graph: `module-depends` is the COMPLETE module→module dependency
   graph — call dependencies (an owned Operation `:delegates` to another module's Operation) UNIONed
   with data-adoption (an owned Operation's `:in`/`:out` is a ref-`Schema` whose `:names` edge reaches
   a `Kind` another module owns). `module-owns` is ownership via `:exposes`/`:owns`/`:child`.
   NB: the `lib.arch` no-mutual-dependency law INLINES an identical copy of these rules (a law's
   `:rules` is macro-time literal data — it cannot reference this var); keep the two copies in sync."
  '[[(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?x]]
    [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :owns]    [?r :rel/to ?x]]
    [(module-owns ?m ?x) [?m :structure/of :lib.code/Module] [?r :rel/from ?m] [?r :rel/kind :child]   [?r :rel/to ?x]]
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
  (set (d/q '[:find ?mn ?nn :in $ %
              :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]
            db module-depends-rules)))
