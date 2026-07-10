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

;; NOTE — `register-check-engine!` is deliberately NOT modelled as a PlugPoint. `check` dispatches to a
;; registered engine, but the sole engine is `cozo-law`, a CO-OWNED module that itself READS the kernel's
;; laws (`laws-of`/`all-structures`). So the registry exists to break a `core-structure ↔ cozo-law`
;; dependency CYCLE, not to invert toward an external provider the kernel can't name. It papers over a
;; layering knot — evaluation belongs WITH the engine, not as a hollow kernel `check` shell — that wants
;; tackling directly (a separate arc), rather than dressing up as an SPI. A genuine plug-point's provider
;; is external-by-design (see the vocab-facing ones below); a cycle-break between co-owned modules is not.

;; the kernel's VOCAB-FACING plug-points — a project's vocab plugs its grammar into the kernel through
;; these, and the kernel names none of them (it ships no vocab, so the inversion is inherent, not a
;; workaround). Coarse first cycle: the named seams + who OWNS them; shapes and the satisfy side (the
;; vocab lives outside this built-system model) are deferred.
(PlugPoint Syntax
  "The authoring-syntax plug-point (`register-syntax!`): a per-structure hook rewriting an instance's
   slots map before parsing (map → map). Vocab registers one per structure that needs sugar; the kernel
   applies whatever is registered at instance-expansion, naming none.")
(PlugPoint Comparator
  "The adherence-comparator plug-point (`register-comparator!`): a `(fn [db design fact] → boolean)` an
   `(agrees {:by …})` demand runs per twin pair. Vocab registers the comparators (e.g. `:signature`); the
   kernel dispatches to the named one, staying type-agnostic.")
(PlugPoint Correspondence
  "The correspondence plug-point (`register-correspondence!`): a per-tag config for how a design concept
   corresponds to extracted code. Vocab declares them via `(correspond …)`; the kernel generates the twin
   + demand laws from whatever is registered, naming no project's correspondences.")

(Operation vocab-rules
  "The datalog rules derived from the live vocabulary, injected into laws/lenses — dispatched
   through the declaration registry (`terms-of`, same module), so no cross-module delegate."
  {:signature [:=> [:cat] [:vector Rule]]})
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
   :realized-by/:altitude/:faithful), plus its free laws, the same set check runs.
   Public so the Cozo law engine can evaluate the identical laws."
  {:signature [:=> [:catn [:sdef :any]] :any]
   :performs  [:throws]})
(Operation direct-scope-tags
  "Qualified tags whose instances carry :structure/of DIRECTLY, so a scoped law can pin ns-precisely
   instead of riding the short-name rule. Excludes facets + realized/coproduct/derived concepts."
  {:signature [:=> [:catn [:structures [:vector :any]]] :any]})

(Module core-structure
  "The defstructure grammar — the registry + value-construction + laws → violations over the graph."
  {:exposes [vocab-rules structure-by-tag value-literal->iv scalar-slot? all-structures
             laws-of direct-scope-tags correspondence* correspondence]
   :owns    [Violation]                          ; the check-output SHAPE the kernel defines (cozo-law's check produces it)
   :offers  [Syntax Comparator Correspondence]    ; the kernel's vocab-facing plug-points (the vocab satisfies them)
   :child   [Rule]})                              ; internal grain: the rules-output type
