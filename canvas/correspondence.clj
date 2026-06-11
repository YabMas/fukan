(ns canvas.correspondence
  "The model↔code correspondence SEAM — kept OFF the domain. Code is a projection of the model, so
   the model must not reference what realizes it; the dependency runs the other way. This is the
   verify-DOWN link of the tower (Subject → Realization → Code): a `SubjectRealization` carries both
   endpoints (a subject concept and the code Module(s) that build it), so the pure subject domain
   never names its realizers.

   (Retired with the faculty self-model: `FacultyRealization`, the `:feeds`-functoriality and
   faculty-realization-completeness laws, and the `DataForm` model↔code data-form check. DataForm
   was a real cross-layer assertion — re-home it onto the subject `Model`/`Act` in a follow-up;
   it needs each concept's data-form Kind re-aligned with its NEW realizer module.)"
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Grouping]]
            ;; Act/Source: the law-scope targets of the completeness laws
            [canvas.vocabulary.subject :refer [Act Source]]
            [canvas.domain.subject :as subj]
            [canvas.realization.kernel :as kernel]
            [canvas.realization.canvas-source :as canvas-source]
            [canvas.realization.target :as target]
            [canvas.realization.probe-surface :as probe-surface]
            [canvas.realization.materialize :as materialize]))

(defstructure SubjectRealization
  "Seam (verify-DOWN): the realization Module(s) that BUILD a subject concept — Layer 1 → Layer 2.
   Authored here, off the pure domain (code is a projection of the model). Crosses subject→code;
   distinct from the domain `Correspondence` (extract ⊣ project, a within-subject relation).

   NB named `SubjectRealization`, not `Realization`: `fukan.target.correspondence/Realization` is
   the op-layer law-host. Distinct namespaces now keep both (the global-tag-namespace fix), but the
   names stay descriptive."
  {:realizes Any        ; the subject concept (an Act / Source / Model / …)
   :by       [:* Any]}) ; the realizing code Module(s)

;; the keystone column — project (the synthesise Act) IS materialization, realized by the
;; `materialize` Module; the rest wire each subject concept to the subsystem that builds it.
(def z-model   (SubjectRealization (realizes subj/model)          (by kernel/core-structure)))
(def z-author  (SubjectRealization (realizes subj/author)         (by canvas-source/canvas-source)))
(def z-extract (SubjectRealization (realizes subj/extract)        (by target/target-clojure)))
(def z-probe   (SubjectRealization (realizes subj/probe)          (by probe-surface/probes)))
(def z-project (SubjectRealization (realizes subj/project)        (by materialize/materialize)))
(def z-corr    (SubjectRealization (realizes subj/correspondence) (by target/target-correspondence)))

(defstructure SubjectCompleteness
  "Law-host: the verify-down completeness of the tower — every Act and every Source (Layer 1) must
   be realized by some Module (Layer 2). A missing realization is drift between what fukan IS and
   what is built. (The realizing Modules' Operations are in turn checked against real `src/`
   functions by the op-layer correspondence — so this reaches the code transitively.)"
  (law "every act is realized by a module"
    (matched-by :realizes :from SubjectRealization :scope Act))
  (law "every source is realized by a module"
    (matched-by :realizes :from SubjectRealization :scope Source)))

(def subject-realization
  (Grouping (child z-model z-author z-extract z-probe z-project z-corr)))
