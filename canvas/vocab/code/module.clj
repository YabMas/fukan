(ns canvas.vocab.code.module
  "Code vocab — `Module`: a code boundary (one namespace), plus the derived module-dependency
   reading. This is also the home of the CROSS-ELEMENT correspondence primitives — the
   `module-corresponds?` name bridge and the `op-twin` pairing built on it (added with the
   correspondence layer, alongside Module's own CallRealization/Fidelity laws; the `ns→Module`
   extraction is added with the extractor)."
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :refer [defstructure]]
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
   NB: the `lib.arch` no-mutual-dependency law INLINES an identical copy of these rules (a law's
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
  (set (d/q '[:find ?mn ?nn :in $ %
              :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]
            db module-depends-rules)))
