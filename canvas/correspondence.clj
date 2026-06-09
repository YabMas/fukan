(ns canvas.correspondence
  "The model↔code correspondence SEAM — kept OFF the domain. Code is a projection of the
   model, so the model must not reference what realizes it; the dependency runs the other
   way. A `FacultyRealization` reifies that mapping: it carries BOTH endpoints (a domain `Faculty`
   and the `Grouping`s that realize it — code Modules and/or model Groupings), so neither the
   pure domain `Faculty` nor the generic language `Grouping` references the other.

   This view also hosts the model↔code laws (`:scope :global`, escaping self-scoping —
   the tell that they aren't about any single concept):
     • materialization functoriality — a dataflow `:feeds` between two realized faculties
       must land as a connection between their realizing modules;
     • realization completeness — a model-reading faculty must have a realization.

   It is the seam, so it knows both sides — it requires the structural perspective (for the
   faculties) and the subsystem modules (the realizers)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocabulary.faculty :refer [Faculty]]
            [lib.grouping :refer [Grouping]]
            [canvas.domain.faculties :as ov]
            [canvas.realization.pipeline :as pipeline]
            [canvas.realization.canvas-source :as canvas-source]
            [canvas.realization.infra :as infra]
            [canvas.realization.kernel :as kernel]
            [canvas.realization.query-engine :as query-engine]
            [canvas.realization.target :as target]
            [canvas.domain.lens :as lens-model]
            [canvas.realization.lens-engine :as lens-engine]
            [canvas.domain.probe :as probe-acts]
            [canvas.realization.probe-surface :as probe-surface]
            [canvas.domain.projection :as projection-model]))

(defstructure FacultyRealization
  "Model↔code correspondence: the `Grouping`s that realize a domain `Faculty`.
   Authored from here (the seam), never on the domain — this node holds both endpoints,
   so the Faculty stays pure (it never names what realizes it)."
  (slot :realizes (one Faculty))     ; the domain concept being realized
  (slot :realizer (many Any)))       ; the realizing view(s) — code Modules and/or model Groupings
                                     ; (stays `Any`: tightening to `(many Grouping)` needs slot
                                     ; type-checks to honor `includes`-satisfaction — deferred)

;; the realization mapping — lifted out of the structural perspective, inverted so the
;; correspondence (not the domain) owns it
(def r-model      (FacultyRealization (realizes ov/Model)
                    (realizer pipeline/model-pipeline canvas-source/canvas-source infra/infra-model)))
(def r-structure  (FacultyRealization (realizes ov/Structure)
                    (realizer kernel/core-structure query-engine/core-rules)))
(def r-target     (FacultyRealization (realizes ov/Target)
                    (realizer target/target-clojure target/target-correspondence)))
(def r-lens       (FacultyRealization (realizes ov/Lens)
                    (realizer lens-model/lens lens-engine/core-lens)))
(def r-probe      (FacultyRealization (realizes ov/Probe)
                    (realizer probe-acts/probe probe-surface/probes)))
(def r-projection (FacultyRealization (realizes ov/Projection)
                    (realizer projection-model/projection probe-surface/probe-code)))

(defstructure MaterializationFunctor
  "Law-host: the materialization functoriality. An internal dataflow `:feeds` from faculty A
   to B — where BOTH are realized — must materialize: some member of A's realizing module(s)
   relates to some member of B's (in either direction; `:feeds` and the realizing `:through`/
   `:calls` often point opposite ways). It surfaces drift where the domain claims a dataflow
   the subsystems don't realize. `:builds-on` (foundation) and `:supplies` (decoupled external
   input) are deliberately excluded — they materialize differently, not as a module link."
  (law "a dataflow feed materializes as a module connection"
    :scope :global
    :offenders '[?fa ?fb]
    :where '[(feeds ?fa ?fb)
             [?fa :structure/of :Faculty] [?fb :structure/of :Faculty]
             [?rza :structure/of :FacultyRealization] (realizes ?rza ?fa) (realizer ?rza ?_ma)
             [?rzb :structure/of :FacultyRealization] (realizes ?rzb ?fb) (realizer ?rzb ?_mb)
             (not-join [?fa ?fb]
               [?rxa :structure/of :FacultyRealization] (realizes ?rxa ?fa) (realizer ?rxa ?ma)
               [?rxb :structure/of :FacultyRealization] (realizes ?rxb ?fb) (realizer ?rxb ?mb)
               [?cx :rel/kind :child] [?cx :rel/from ?ma] [?cx :rel/to ?x]
               [?cy :rel/kind :child] [?cy :rel/from ?mb] [?cy :rel/to ?y]
               (or-join [?x ?y]
                 (and [?r :rel/from ?x] [?r :rel/to ?y])
                 (and [?r2 :rel/from ?y] [?r2 :rel/to ?x])))]))

(defstructure RealizationCompleteness
  "Law-host: a model-reading faculty must have a realization. A faculty that `:reads` the
   Model claims a capability operating on it, so it MUST be realized by some module. (Moved
   here off `Faculty`: it's a correspondence requirement, not a property of the concept.)"
  (law "a model-reading faculty has a realization"
    :scope :global
    :offenders '[?f]
    :where '[[?f :structure/of :Faculty]
             [?model :entity/name "Model"] [?model :structure/of :Faculty]
             [?rd :rel/from ?f] [?rd :rel/kind :reads] [?rd :rel/to ?model]
             (not-join [?f] [?rz :structure/of :FacultyRealization] (realizes ?rz ?f))]))

;; ── a concept's DATA FORM — the real, followable link from a designed Faculty to the
;;    code data-type that embodies it (distinct from FacultyRealization, which names the
;;    subsystems that BUILD it). The `Model` faculty's data form is the kernel's StructureDb.
(defstructure DataForm
  "A domain `Faculty` realized as a code data-type — the concept's data FORM. A real queryable
   edge (Faculty → Kind), so you can follow from the designed concept to the code structure that
   embodies it, and ASSERT that the subsystems realizing the concept actually traffic in it."
  (slot :concept (one Faculty))
  (slot :form    (one Kind)))

(def model-data-form      (DataForm (concept ov/Model)      (form kernel/StructureDb)))      ; the model IS a structure db
(def probe-data-form      (DataForm (concept ov/Probe)      (form probe-surface/Finding)))    ; a probe yields a Finding
(def projection-data-form (DataForm (concept ov/Projection) (form probe-surface/Instruction))) ; a projection renders an Instruction

(defstructure DataFormAdherence
  "Law-host: a designed concept's data form must be present in its realizing CODE — every
   `Module` realizing a faculty that has a data form must own an `Operation` whose input or
   output IS that data-form Kind. The structural bridge: the code that realizes the `Model`
   concept must actually produce/consume the `StructureDb` it is. (Modelled Operations are in
   turn asserted against real functions by the op-layer correspondence law, so this reaches the
   code transitively.)"
  (law "a subsystem realizing a faculty traffics in the faculty's data form"
    :scope :global
    :offenders '[?fac ?sub]
    :where '[[?df :structure/of :DataForm] (concept ?df ?fac) (form ?df ?k)
             [?fr :structure/of :FacultyRealization] (realizes ?fr ?fac) (realizer ?fr ?sub)
             [?sub :structure/of :Module] (named ?sub ?sn)
             (not-join [?sub ?sn ?k]
               [?op :structure/of :Operation] (in-module ?op ?sn)
               (or-join [?op ?sh]
                 (and [?o :rel/from ?op] [?o :rel/kind :out] [?o :rel/to ?sh])
                 (and [?i :rel/from ?op] [?i :rel/kind :in]  [?i :rel/to ?sh]))
               [?ty :rel/from ?sh] [?ty :rel/kind :names] [?ty :rel/to ?k])]))

(def correspondence
  (Grouping (child r-model r-structure r-target r-lens r-probe r-projection
                 model-data-form probe-data-form projection-data-form)))
