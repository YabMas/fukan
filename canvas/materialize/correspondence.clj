(ns canvas.materialize.correspondence
  "The model↔code correspondence SEAM — kept OFF the domain. Code is a projection of the
   model, so the model must not reference what realizes it; the dependency runs the other
   way. A `FacultyRealization` reifies that mapping: it carries BOTH endpoints (a domain `Faculty`
   and the subsystem `Module`s that realize it), so neither the pure domain `Faculty` nor
   the generic language `Module` references the other.

   This view also hosts the model↔code laws (`:scope :global`, escaping self-scoping —
   the tell that they aren't about any single concept):
     • materialization functoriality — a dataflow `:feeds` between two realized faculties
       must land as a connection between their realizing modules;
     • realization completeness — a model-reading faculty must have a realization.

   It is the seam, so it knows both sides — it requires the structural perspective (for the
   faculties) and the subsystem modules (the realizers)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.domain.faculty :refer [Faculty]]
            [canvas.language.grouping :refer [Module]]
            [canvas.perspectives.structure.overview :as ov]
            [canvas.materialize.pipeline :as pipeline]
            [canvas.materialize.canvas-source :as canvas-source]
            [canvas.materialize.infra :as infra]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.query-engine :as query-engine]
            [canvas.materialize.target :as target]
            [canvas.domain.lens-model :as lens-model]
            [canvas.materialize.lens-engine :as lens-engine]
            [canvas.domain.probe-acts :as probe-acts]
            [canvas.materialize.probe-surface :as probe-surface]
            [canvas.domain.projection-model :as projection-model]))

(defstructure FacultyRealization
  "Model↔code correspondence: the subsystem `Module`s that realize a domain `Faculty`.
   Authored from here (the seam), never on the domain — this node holds both endpoints,
   so the Faculty stays pure (it never names what realizes it)."
  (slot :realizes (one Faculty))     ; the domain concept being realized
  (slot :realizer (many Any)))       ; the realizing view(s) — code Subsystems and/or model Modules

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

(def correspondence
  (Module (child r-model r-structure r-target r-lens r-probe r-projection)))
