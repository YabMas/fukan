(ns canvas.correspondence
  "The model↔code correspondence SEAM — kept OFF the domain. Code is a projection of the model, so
   the model must not reference what realizes it; the dependency runs the other way. This is the
   verify-DOWN link of the tower (Subject → Realization → Code): a `SubjectRealization` carries both
   endpoints (a subject concept and the code Module(s) that build it), so the pure subject domain
   never names its realizers.

   A realization also records the concept's DATA FORM when it has one — the code `Kind` that IS the
   concept's shape (`:form`), a facet of the same realization since the realizing module owns it.
   That data-shape correspondence is checked; the flow→signature half is harder and stays deferred."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Grouping]]
            [lib.code :refer [Kind]]
            ;; Act/Source: the law-scope targets of the completeness laws
            [canvas.vocabulary.subject :refer [Act Source]]
            [canvas.domain.subject :as subj]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.canvas-source :as canvas-source]
            [canvas.architecture.target :as target]
            [canvas.architecture.probe-surface :as probe-surface]
            [canvas.architecture.materialize :as materialize]))

(defstructure SubjectRealization
  "Seam (verify-DOWN): the realization of a subject (design) concept — Layer 1 → Layer 2. The link
   lives HERE, on a dedicated node, precisely so it is element-AGNOSTIC: it names the realizing code
   element(s) in `:by` — a `Module` today; an `Operation`, a `Kind`, or any code element we invent
   tomorrow, with ZERO change to those structures — and the concept's data shape in `:form` (the
   `Kind` that IS it, when it has one; a facet of the realization, since the realizer owns it:
   Model→StructureDb, probe→Finding, project→Instruction). Embedding `:realizes` on `Module` would
   force the same slot onto `Operation`, `Kind`, … in turn; reifying the relation expresses 'code
   realizes design' ONCE, for any code element. Authored off the pure domain (code is a projection
   of the model). Crosses subject→code; distinct from the domain `Correspondence` (extract ⊣
   project, a within-subject relation).

   NB named `SubjectRealization`, not `Realization`: `fukan.target.correspondence/Realization` is
   the op-layer law-host. Distinct namespaces keep both, but the names stay descriptive."
  {:realizes Any         ; the subject (design) concept (an Act / Source / Model / …)
   :by       [:* Any]    ; the realizing code element(s) — any element, not just Module
   :form     [:? Kind]}  ; its data-form, when it has one — a Kind owned by one of :by
  ;; the data-shape half of the abstract↔code correspondence: a recorded form must be owned by
  ;; the realizer, so renaming/moving the data-shape — or re-homing the realization — drifts.
  (law "a realization's data-form is owned by its realizing module"
    :offenders '[?sr]
    :where '[(SubjectRealization ?sr) (form ?sr ?k)
             (not-join [?sr ?k] (by ?sr ?m) (owns ?m ?k))]))

;; the keystone column — project (the synthesise Act) IS materialization, realized by the
;; `materialize` Module; the rest wire each subject concept to the subsystem that builds it.
;; Model / probe / project also carry their data form; the Sources + correspondence have none.
(SubjectRealization z-model   {:realizes subj/model          :by [kernel/core-structure]        :form kernel/StructureDb})
(SubjectRealization z-author  {:realizes subj/author         :by [canvas-source/canvas-source]})
(SubjectRealization z-extract {:realizes subj/extract        :by [target/target-clojure]})
(SubjectRealization z-probe   {:realizes subj/probe          :by [probe-surface/probes]         :form probe-surface/Finding})
(SubjectRealization z-project {:realizes subj/project        :by [materialize/materialize]      :form materialize/Instruction})
(SubjectRealization z-corr    {:realizes subj/correspondence :by [target/target-correspondence]})

(defstructure SubjectCompleteness
  "Law-host: the verify-down completeness of the tower — every Act and every Source (Layer 1) must
   be realized by some Module (Layer 2). A missing realization is drift between what fukan IS and
   what is built. (The realizing Modules' Operations are in turn checked against real `src/`
   functions by the op-layer correspondence — so this reaches the code transitively.)"
  (law "every act is realized by a module"
    (matched-by :realizes :from SubjectRealization :scope Act))
  (law "every source is realized by a module"
    (matched-by :realizes :from SubjectRealization :scope Source)))

(Grouping subject-realization
  {:child [z-model z-author z-extract z-probe z-project z-corr]})
