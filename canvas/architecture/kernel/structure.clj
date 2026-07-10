(ns canvas.architecture.kernel.structure
  "Self-spec: fukan's defstructure GRAMMAR (`fukan.canvas.core.structure`) — a boundary sketch. The
   defstructure layer (Structure = slots + laws) is NOT hand-modelled: grammar reflection
   (`canvas.vocab.grammar/with-grammar`) derives it from the live registry, where it can never drift. What
   remains hand-modelled here is the registry surface, value-construction (`value-literal->iv`), and
   the one capability `check` (laws → violations): the canonical integrity inspect, modelled because
   code is a projection of the model 1-on-1. The NODE substrate it sits on — `Node`/`Relation`/
   `InstanceValue`/`StructureDb` + node identity — lives one layer down in `core-substrate`."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.vocab.code.plug-point :refer [PlugPoint]]
            [canvas.architecture.kernel.substrate :as substrate]))

;; ── owned data-shapes + the exposed capability ──────────────────────────────────────────────
(Kind Violation [:map [:structure :keyword] [:law :string] [:offenders [:vector [:vector :any]]]])
(Kind Rule)

;; the CHECK-ENGINE plug-point: `core-structure` OWNS this plug-point (a `check` backend claimed by db
;; type) but does not implement it — `register-check-engine!` is the attach hook; `cozo-law` satisfies it.
(PlugPoint CheckEngine
  "The `check` backend plug-point others register via `register-check-engine!`: a claims?/check pair the
   kernel dispatches to without naming the backend (the dependency inverts — the engine names the kernel)."
  {:shape [:map [:claims? [:=> [:catn [:db :any]] :boolean]] [:check [:=> [:catn [:db :any]] :any]]]})

(Operation vocab-rules
  "The datalog rules derived from the live vocabulary, injected into laws/lenses — dispatched
   through the declaration registry (`terms-of`, same module), so no cross-module delegate."
  {:signature [:=> [:cat] [:vector Rule]]})
(Operation check
  "Run every structure's laws over the model db; yield the violations."
  {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector Violation]]
   :guidance  "Inject vocab-rules into each law's :where so laws read at domain altitude; route negation through rules to dodge datascript's empty-relation not-join gotcha."})
(Operation structure-by-tag
  "Look up a registered structure definition (slots + laws) by its tag."
  {:signature [:=> [:catn [:tag :keyword]] :any]})
(Operation value-literal->iv
  "Build a ^:value InstanceValue for a value-structure tag from a data literal — the one
   value-construction path; the kernel's `typing/reflect-type` builds a dialect's type
   subgraphs through it (the dialect contributes only its tag, never reaching in)."
  {:signature [:=> [:catn [:tag :keyword] [:literal :any]] :any]
   :performs  [:throws]})
(Operation scalar-slot?
  "Whether a slot stores a leaf VALUE (vs. a relation to a node) — drives value-vs-ref handling."
  {:signature [:=> [:catn [:slot :any]] :boolean]})
(Operation all-structures
  "The live registry roll-call — every registered structure definition (slots + laws). The seam
   grammar reflection reads to project the registry onto the model."
  {:signature [:=> [:cat] [:vector :any]]})
(Operation correspondence*
  "The seam of an sdef seq as data; guards duplicate law keys (throws)."
  {:signature [:=> [:catn [:sdefs [:sequential :any]]] :map]
   :performs  [:throws]})
(Operation correspondence
  "The live registry's correspondence seam — see correspondence*."
  {:signature [:=> [:cat] :map]
   :performs  [:throws]
   :delegates [correspondence*]})
(Operation laws-of
  "Every law of a structure — slot-derived cardinality/type laws, correspondence-demand laws
   (generated from (realized …)/(covered …) sub-forms and from relation slots carrying
   :realized-by/:altitude/:faithful), plus its free laws, the same set check runs.
   Public so the Cozo law engine can evaluate the identical laws."
  {:signature [:=> [:catn [:sdef :any]] :any]
   :performs  [:throws]})
(Operation direct-scope-tags
  "Qualified tags whose instances carry :structure/of DIRECTLY, so a scoped law can pin ns-precisely
   instead of riding the short-name rule. Excludes facets + realized/coproduct/derived concepts."
  {:signature [:=> [:catn [:structures [:vector :any]]] :any]})
(Operation register-check-engine!
  "Register an alternative `check` backend (the Cozo law engine), claimed by db type — so `check`
   dispatches to it without the kernel naming the backend (mirrors the typing plug-point)."
  {:signature [:=> [:catn [:engine :any]] :nil]
   :performs  [:state]})
(Operation violations-of
  "The offender eids of the law keyed k — the generic reader behind every law-specific worklist (filters check by the law's stable :key)."
  {:signature [:=> [:catn [:db substrate/StructureDb] [:k :keyword]] :any]
   :delegates [check]})

(Module core-structure
  "The defstructure grammar — the registry + value-construction + laws → violations over the graph."
  {:exposes [check vocab-rules structure-by-tag value-literal->iv scalar-slot? all-structures
             laws-of direct-scope-tags register-check-engine! violations-of
             correspondence* correspondence]
   :owns    [Violation]                          ; the check output shape (others adopt by name)
   :offers  [CheckEngine]                         ; the check-engine plug-point (cozo-law satisfies it)
   :child   [Rule]})                              ; internal grain: the rules-output type
