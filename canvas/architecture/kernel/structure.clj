(ns canvas.architecture.kernel.structure
  "Self-spec: fukan's defstructure GRAMMAR (`fukan.canvas.core.structure`) — a boundary sketch. The
   defstructure layer (Structure = slots + laws) is NOT hand-modelled: grammar reflection
   (`fukan.canvas.core.reflect/with-grammar`) derives it from the live registry, where it can never drift. What
   remains hand-modelled here is the registry surface, value-construction (`value-literal->iv`), and
   the one capability `check` (laws → violations): the canonical integrity inspect, modelled because
   code is a projection of the model 1-on-1. The NODE substrate it sits on — `Node`/`Relation`/
   `InstanceValue`/`StructureDb` + node identity — lives one layer down in `core-substrate`."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]))

;; ── owned data-shapes + the exposed capability ──────────────────────────────────────────────
(Kind Violation [:map [:structure :keyword] [:law :string] [:offenders [:vector [:vector :any]]]])
(Kind Rule)
(Kind Form
  "A rendered Clojure code form — a defstructure form or an authored instance form (the print-duals'
   faithful render). Owned here as the code-form OF the grammar; the print-dual projections produce it.")

(Operation vocab-rules
  "The datalog rules derived from the live vocabulary, injected into laws/lenses — lowered through
   the kernel's closed declaration algebra (`terms-of`, same module)."
  {:signature [:=> [:cat] [:vector Rule]]
   :performs  [:throws :state]})             ; reads registries; rejects closed-head contributors
;; `check` (+ its readers `violations-of`/`violation-names`) is EVALUATION — it lives in the engine
;; (`cozo-law`), not here. The kernel DEFINES laws (`laws-of`/`all-structures`); the engine evaluates.
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
   :realized-by/:faithful), plus its free laws, the same set check runs.
   Public so the Cozo law engine can evaluate the identical laws."
  {:signature [:=> [:catn [:sdef :any]] :any]
   :performs  [:throws :state]})
(Operation direct-scope-tags
  "Qualified tags whose instances carry :structure/of DIRECTLY, so a scoped law can pin ns-precisely
   instead of riding the short-name rule. Excludes facets + realized/coproduct/derived concepts."
  {:signature [:=> [:catn [:structures [:vector :any]]] :any]})

(Module core-structure
  "The defstructure grammar — the registry + value-construction + laws → violations over the graph."
  {:child [vocab-rules structure-by-tag value-literal->iv scalar-slot? all-structures
           laws-of direct-scope-tags correspondence* correspondence
           Violation Form                         ; check-output SHAPE (cozo-law produces it) + the print-dual code-form (projections produce it)
           Rule]})                                ; the rules-output type
