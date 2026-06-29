(ns canvas.architecture.projection.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the inverse of the
   target layer's extraction. It composes per-primitive `render` instructions (a multimethod — the
   open extension point, modelled for coverage but its inline-method fan-out is not) over a Lens's
   focus, projecting the model into an implementation specification. `materialize-view` is the
   public entry. `core.lens` lives in `canvas.architecture.kernel.lens`."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.kernel.typing :as typing]
            [canvas.architecture.kernel.lens :as lens-engine]
            [canvas.architecture.projection.finding :as finding]))

(Module materialize
  "Project the model down into an implementation spec, through a Lens focus + a Projection."
  (Kind Lens) (Kind Instruction) (Kind Projection)
  (Kind ProjectionName :string) (Kind ModuleName :string)
  (Kind Clause) (Kind Eid :int)
  (Kind FindingMap [:map-of ProjectionName finding/Finding])   ; read-all's return: {projection-name → Finding}
  (Operation materialize-view "Render a lens focus under Blueprint (the default projection)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:lens Lens]] Instruction]
     :performs  [:throws]})                        ; reaches the lens engine's query-compiler throw
  (Operation materialize-over "Render a refined focus (node-set) under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:focus [:vector Eid]]] Instruction]
     :performs  [:throws]                          ; the renderers read the graph through the query compiler
     :delegates [query/q query/entity]})           ; the module reads node facts via the kernel query layer
  (Operation materialize-focus "Render the nodes an ad-hoc :where clause selects, under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:clauses [:vector Clause]]] Instruction]
     :performs  [:throws]                          ; via focus-nodes
     :delegates [lens-engine/focus-nodes]})
  (Operation materialize-module "Render a module's Operations under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:module ModuleName]] Instruction]
     :performs  [:throws]})                        ; reaches the lens engine's query-compiler throw
  (Operation materialize-projection "Render a modelled Projection through its own lens (model-driven)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj Projection]] Instruction]
     :performs  [:throws]                          ; via evaluate-lens
     :delegates [lens-engine/evaluate-lens]})
  (Operation render "Render a single node under a projection (composes the per-primitive render-base multimethod)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:eid Eid]] Instruction]
     :performs  [:throws]})                         ; the renderers read the graph through the query compiler
  (Operation materialize-finding "Compose a finding's observation foci into a projection — the reading→projection seam."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:finding :any]] Instruction]
     :performs  [:throws]})                         ; via materialize-over
  (Operation ^:private render-base
    "The per-(projection, kind) render dispatch point. Its defmethods have inline bodies (no named
     handler ops), so it carries no :dispatches-to fan-out — modelled for coverage."
    {})
  ;; ── the readings: a Projection whose target artifact is a Finding (the read dual of Blueprint) ──
  (Operation ^:private render-finding
    "The per-projection reading-render dispatch point — the read dual of render-base. Its defmethods
     route to named finding helpers in inline bodies (not extracted), so it carries no :dispatches-to
     fan-out — modelled for coverage."
    {})
  ;; the four reading renderers — each aggregates its lens focus into observations, delegating to the
  ;; finding constructors (this is the materialize→finding coupling the readings introduce, declared here)
  (Operation ^:private survey-finding "Count the focus's nodes by structure kind."
    {:performs [:throws] :delegates [finding/finding finding/observation]})
  (Operation ^:private patterns-finding "Group the focus's relations by structural triplet (the recurring ones)."
    {:performs [:throws] :delegates [finding/finding finding/observation]})
  (Operation ^:private consistency-finding "Group the focus's Operations by name (ambiguous across modules)."
    {:performs [:throws] :delegates [finding/finding finding/observation]})
  (Operation ^:private callers-finding "Rank the focus's nodes by relation degree (the hotspots)."
    {:performs [:throws] :delegates [finding/finding finding/observation]})
  (Operation read-projection "Run a reading projection: evaluate its :through lens, render the focus into a Finding."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj-eid Eid]] finding/Finding]
     :performs  [:throws]                          ; via evaluate-lens
     :delegates [lens-engine/evaluate-lens]})
  (Operation read-all "Run every reading projection present in the db → a map of findings."
    {:signature [:=> [:catn [:db substrate/StructureDb]] FindingMap]
     :performs  [:throws]})                         ; via read-projection / the query compiler
  (Operation ^:private operation-malli
    "The Blueprint method's schema-emitter: an Operation's faithful `:malli/schema` form, each shape
     rendered through the type dialect. A named top-level helper (not inline in the defmethod) so the
     materialize→typing dependency is a real, extractable call — the inline defmethod body is not."
    {:delegates [typing/render-type]}))
