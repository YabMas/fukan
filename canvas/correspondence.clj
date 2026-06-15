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
            ;; the subject faculties are PORTRAITS (grammar nodes); realizations name them by tag
            [canvas.subject :as subj]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.canvas-source :as canvas-source]
            [canvas.architecture.target :as target]
            [canvas.architecture.probe-surface :as probe-surface]
            [canvas.architecture.materialize :as materialize]))

(defn ^:export realizes->tag
  "SubjectRealization's authoring syntax (the `(syntax …)` hook, map → map): `:realizes` is authored
   as the realized faculty's structure SYMBOL (`subj/Model` / `subj/Source` / `subj/Lens` /
   `subj/Projection`); rewrite it to the qualified tag STRING (matching reflection's `:val/tag`), so
   the completeness law can match the realization to the faculty's reflected grammar node. The symbol
   resolves through its var (a structure's identity is its defining ns + name), so a typo throws at
   macro-expansion. A non-symbol passes through."
  [m]
  (if-let [s (:realizes m)]
    (if (symbol? s)
      (if-let [v (resolve s)]
        (let [mm (meta v)]
          (assoc m :realizes (str (keyword (str (ns-name (:ns mm))) (name (:name mm))))))
        (throw (ex-info (str "SubjectRealization :realizes — unknown faculty " s) {:realizes s})))
      m)
    m))

(defstructure SubjectRealization
  "Seam (verify-DOWN): the realization of a subject (design) concept — Layer 1 → Layer 2. The link
   lives HERE, on a dedicated node, precisely so it is element-AGNOSTIC: it names the realizing code
   element(s) in `:by` — a `Module` today; an `Operation`, a `Kind`, or any code element we invent
   tomorrow, with ZERO change to those structures — and the concept's data shape in `:form` (the
   `Kind` that IS it, when it has one; a facet of the realization, since the realizer owns it:
   Model→StructureDb, focus→Finding, project→Instruction). Embedding `:realizes` on `Module` would
   force the same slot onto `Operation`, `Kind`, … in turn; reifying the relation expresses 'code
   realizes design' ONCE, for any code element. Authored off the pure domain (code is a projection
   of the model). Crosses subject→code; the realized faculty, BY QUALIFIED TAG — the faculties are
   portraits (grammar nodes), so the seam names them, it does not edge to an instance.

   NB named `SubjectRealization`, not `Realization`: `fukan.target.correspondence/Realization` is
   the op-layer law-host. Distinct namespaces keep both, but the names stay descriptive."
  {:realizes :string     ; the qualified tag of the realized faculty (authored as its symbol)
   :by       [:* Any]    ; the realizing code element(s) — any element, not just Module
   :form     [:? Kind]}  ; its data-form, when it has one — a Kind owned by one of :by
  (syntax realizes->tag)
  ;; the data-shape half of the abstract↔code correspondence: a recorded form must be owned by
  ;; the realizer, so renaming/moving the data-shape — or re-homing the realization — drifts.
  (law "a realization's data-form is owned by its realizing module"
    :offenders '[?sr]
    :where '[(SubjectRealization ?sr) (form ?sr ?k)
             (not-join [?sr ?k] (by ?sr ?m) (owns ?m ?k))]))

;; one realization per use-side faculty; project (the Projection) IS materialization, the read
;; faculty (Lens) is realized by the probe runner AND the correspondence checker, the in-fold
;; (Source) by both ingestion modules. :realizes names the faculty by tag (the syntax hook resolves
;; the symbol); :form records the data shape where the concept has one.
(SubjectRealization z-model      {:realizes subj/Model      :by [kernel/core-structure]                              :form kernel/StructureDb})
(SubjectRealization z-source     {:realizes subj/Source     :by [canvas-source/canvas-source target/target-clojure]})
(SubjectRealization z-lens       {:realizes subj/Lens       :by [probe-surface/probes target/target-correspondence] :form probe-surface/Finding})
(SubjectRealization z-projection {:realizes subj/Projection :by [materialize/materialize]                            :form materialize/Instruction})

(defstructure SubjectCompleteness
  "Law-host: verify-down completeness — every use-side subject faculty (a `Source`, a `Lens`, or a
   `Projection`) must be realized by some Module (Layer 2). A missing realization is drift between
   what fukan IS and what is built. The faculties are PORTRAITS (grammar, no instances), so the law
   ranges over their reflected `Structure` nodes (present because `build-model` seeds the subject ns
   into reflection) and checks each is named by a `SubjectRealization`'s `:realizes` tag. The fully
   qualified `:val/tag` makes this ns-precise for free — the subject `Lens`/`Projection` never
   collide with the `lib.lens` grammar's same-short-named structures.

   Negation is routed through a rule (`realized-faculty`) — not a raw `not-join` — to dodge
   DataScript's empty-relation gotcha (a `not-join` whose inner pattern matches zero tuples fails
   rather than succeeds; encapsulated in the kernel's combinators; reproduced manually here because
   the check ranges over grammar nodes, not structure instances, so no combinator fits)."
  ;; the tag strings in the :where set MUST equal (str :canvas.subject/<Tag>) — renaming
  ;; canvas.subject silently stops the law firing; `subject-completeness-flags-unrealized-faculties`
  ;; is the regression that guards this.
  (law "every use-side faculty (Source/Lens/Projection) is realized by a module"
    :scope :global
    :offenders '[?node]
    :rules '[[(realized-faculty ?tag)
              [?sr :structure/of :canvas.correspondence/SubjectRealization]
              [?sr :val/realizes ?tag]]]
    :where '[[?node :structure/of :lib.grammar/Structure] [?node :val/tag ?tag]
             [(clojure.core/contains? #{":canvas.subject/Source" ":canvas.subject/Lens" ":canvas.subject/Projection"} ?tag)]
             (not (realized-faculty ?tag))]))

(Grouping subject-realization
  {:child [z-model z-source z-lens z-projection]})
