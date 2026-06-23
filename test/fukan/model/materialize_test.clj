(ns fukan.model.materialize-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            ;; the lens act (the grammar Lens/Projection/Mapping + the engine) + the real
            ;; self-model instance vars an ad-hoc contextualization composes over (Blueprint, survey)
            [fukan.canvas.core.lens :as lens :refer [Lens Projection Mapping]]
            [canvas.instruments.projections :refer [Blueprint]]
            [canvas.instruments.lenses :refer [survey]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.projection.probes :as probes]
            [fukan.model.materialize :as m]
            [fukan.model.pipeline :as pipeline]
            ;; loaded so the vocab-derived rules carry an Operation kind-rule (coverage /
            ;; drift lenses reference it) and the drift lens's correspondence predicate
            ;; resolves
            [canvas.vocab.code.extractor :as target]
            ;; the code vocab — `corr` for module-corresponds?, + the macros for the self-contained
            ;; worked-example fixture (a Module with a rich-signature Operation), which replaces the
            ;; retired extractor self-spec the tests once read
            [canvas.vocab.code.kind :refer [Kind]]
            [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :as corr :refer [Module]]))

;; materialize is the pure LOWER direction — it projects the design model alone
;; (build-model nil = design-only, no extraction). Ad-hoc lenses/projections are
;; top-level value defs assembled and unioned onto the model.

(Lens ^{:name "target"} mvl-target
  {:focus  "the target extractor's stages"
   :select ["target stages" '[(Operation ?n) (in-module ?n "target-clojure")]]})
(Lens ^{:name "none"} ef-none
  {:focus  "nothing"
   :select ["nothing" '[(Operation ?n) (in-module ?n "no-such-module")]]})

;; an ad-hoc contextualization of the SHIPPED Blueprint — no Refactor renderer exists.
;; projection/Blueprint (+ its survey lens) is included in the assembled fragment so its
;; var-ref resolves; union with the model then dedups it.
(Lens ^{:name "stages"} acb-stages
  {:focus  "x"
   :select ["target stages" '[(Operation ?n) (in-module ?n "target-clojure")]]})
(Projection ^{:name "Refactor"} acb-Refactor
  {:through        acb-stages
   :contextualizes Blueprint
   :context        "Refactor the existing implementation to match these specs:"})

;; an ad-hoc Docs projection whose name selects the Docs renderers
(Lens ^{:name "stages"} mprd-stages
  {:focus  "target stages"
   :select ["target stages" '[(Operation ?n) (in-module ?n "target-clojure")]]})
(Projection ^{:name "Docs"} mprd-Docs
  {:through mprd-stages
   :maps    [(Mapping {:from "a function" :to "a doc section"})]})

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

;; tags are ns-qualified; tests pass a short kind handle and match by its name
(defn- by-kind-name [db kind n]
  (->> (d/q '[:find ?e ?k :in $ ?n :where [?e :structure/of ?k] [?e :entity/name ?n]] db n)
       (filter (fn [[_ k]] (= (name kind) (name k)))) ffirst))

;; A self-contained worked example: a code Module `target-clojure` exposing a rich-signature
;; Operation `extract` (paths: [Path] → StructureDb, performs :io). It reproduces what the retired
;; extractor self-spec gave the materialize/render tests, without coupling to any one self-model
;; module. `model*` unions it onto the design-only model.
(Kind ^{:name "Path"} fx-path :string)
(Kind ^{:name "StructureDb"} fx-sdb)
(Operation ^{:name "extract"} fx-extract
  {:signature [:=> [:catn [:paths [:vector fx-path]]] fx-sdb]
   :performs  [:io]})
(Module ^{:name "target-clojure"} fx-target {:exposes [fx-extract] :owns [fx-path fx-sdb]})

(defn- model*
  "The design-only self-model with the worked-example fixture (`target-clojure`/`extract`) unioned in."
  []
  (cs/union-dbs [(pipeline/build-model nil)
                 (a/assemble-vars [#'fx-path #'fx-sdb #'fx-extract #'fx-target])]))

(deftest render-dispatches-per-projection-and-kind-and-composes-references
  (testing "render [Blueprint :Operation] composes its :Schema renders (via dispatch) into the signature"
    (let [db   (model*)
          text (m/render db "Blueprint" (by-kind-name db :Operation "extract"))]
      (is (str/includes? text "Implement `extract` in module `target-clojure`"))
      (is (str/includes? text "paths: [Path]") "the :in Schema rendered via render :Schema (vector → [Kind])")
      (is (str/includes? text "→ StructureDb"))
      (is (str/includes? text "Effects: io")))))

(deftest the-same-node-renders-differently-per-projection
  (testing "Blueprint and Docs are distinct targets over the same focus — the generalization"
    (let [db        (model*)
          extract   (by-kind-name db :Operation "extract")
          blueprint (m/render db "Blueprint" extract)
          docs      (m/render db "Docs" extract)]
      (is (str/includes? blueprint "Implement `extract`") "Blueprint → an implementation spec")
      (is (str/includes? docs "### extract") "Docs → a reference doc section")
      (is (str/includes? docs "**Grouping:** target-clojure"))
      (is (str/includes? docs "**Takes:** paths") "shapes still compose via dispatch under Docs")
      (is (not= blueprint docs) "same node, different artifact per projection"))))

(deftest materialize-view-composes-a-lens-focus-under-blueprint
  (testing "materialize-view renders each primitive a lens selects, under Blueprint (the default)"
    (let [model   (model*)
          lens-db (a/assemble-vars [#'mvl-target])
          db      (cs/union-dbs [model lens-db])
          view    (m/materialize-view db (by-name db "target"))]
      (is (str/includes? view "Implement `extract`"))
      (is (str/includes? view "paths: [Path]")))))

(deftest empty-focus-materializes-to-empty-string
  (testing "a lens whose focus is empty composes to nothing"
    (let [model   (model*)            ; loads the canvas vocab (the (Operation …) rule)
          lens-db (a/assemble-vars [#'ef-none])
          db      (cs/union-dbs [model lens-db])]
      (is (= "" (m/materialize-view db (by-name db "none")))))))

(deftest materialize-module-composes-a-modules-stages
  (testing "materialize-module renders a module's Operations under a projection — no stored lens"
    (let [db (model*)]
      (let [bp (m/materialize-module db "Blueprint" "core-lens")]
        (is (str/includes? bp "Implement `evaluate-lens`"))
        (is (str/includes? bp "Implement `refine`"))
        (is (str/includes? bp "Delegates: vocab-rules") "the cross-boundary dependency renders inline"))
      (let [docs (m/materialize-module db "Docs" "core-lens")]
        (is (str/includes? docs "### evaluate-lens"))
        (is (str/includes? docs "### refine")))
      (is (= "" (m/materialize-module db "Blueprint" "no-such-module")) "unknown module → empty"))))

(deftest materialize-focus-takes-ad-hoc-clauses
  (testing "materialize-focus renders the nodes an ad-hoc :where clause selects, under a projection"
    (let [db   (model*)
          spec (m/materialize-focus db "Blueprint" '[(Operation ?n) (in-module ?n "materialize")])]
      (is (str/includes? spec "Implement `materialize-view`")
          "the materialize module's own modelled Operation is projected"))))

(deftest shipped-lenses-with-queries-are-evaluable
  (testing "every retrofitted self-model lens resolves to a node-set (no prose-only throw)"
    (let [model (model*)
          code  (target/extract "test/fixtures/target/sample.clj")   ; Operations, so coverage/drift resolve
          db    (cs/union-dbs [model code])]
      ;; the predicate the drift lens query invokes
      (is (corr/module-corresponds? "core-structure" "fukan.canvas.core.structure"))
      (doseq [ln ["survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"]]
        (let [focus (lens/evaluate-lens db (by-kind-name db :Lens ln))]
          (is (set? focus) (str "lens " ln " evaluates to a node-set"))))
      (is (seq (lens/evaluate-lens db (by-kind-name db :Lens "survey")))
          "survey (whole model) is non-empty")
      (is (seq (lens/evaluate-lens db (by-kind-name db :Lens "coverage")))
          "coverage selects the extracted Operations"))))

(deftest materialize-projection-runs-the-shipped-blueprint
  (testing "Blueprint (through the now-query-bearing survey lens) materializes the model's Operations"
    (let [db  (model*)
          out (m/materialize-projection db (by-kind-name db :Projection "Blueprint"))]
      ;; survey selects the whole model; compose renders only the kinds Blueprint covers
      ;; (named Operations), not every node as a bare name
      (is (str/includes? out "Implement `extract`"))
      (is (str/includes? out "Implement `check`") "Operations from across modules are projected")
      (is (> (count (re-seq #"This is an implementation specification" out)) 3)
          "several Operations projected; bare Kinds are not rendered standalone"))))

(deftest driftclose-contextualizes-blueprint-not-duplicates-it
  (testing "DriftClose composes Blueprint — its specs framed by a drift context, not a parallel renderer"
    ;; design-only model: no extracted code → every modelled Operation drifts → the drift
    ;; lens selects them all → DriftClose = Blueprint's specs under a drift-closing context.
    (let [db  (model*)
          out (m/materialize-projection db (by-kind-name db :Projection "DriftClose"))]
      (is (str/includes? out "modelled but have no realizing function") "the drift context preamble")
      (is (str/includes? out "Implement `extract` in module `target-clojure`")
          "the body IS Blueprint's spec — composed, not a bespoke close-instruction")
      ;; delegation: a single node under DriftClose renders identically to under Blueprint
      ;; (no DriftClose-specific renderer — the context is applied only at the view level)
      (let [extract (by-kind-name db :Operation "extract")]
        (is (= (m/render db "Blueprint" extract) (m/render db "DriftClose" extract))
            "DriftClose delegates per-node rendering to its base Blueprint")))))

(deftest a-context-composes-over-any-base
  (testing "the same mechanism composes Blueprint with an arbitrary context — e.g. a refactor framing"
    (let [model   (model*)
          ;; the fragment includes Blueprint (+ its survey lens) so the contextualizes
          ;; var-ref resolves; union with the model then dedups them
          proj-db (a/assemble-vars [#'acb-stages #'acb-Refactor #'Blueprint #'survey])
          db      (cs/union-dbs [model proj-db])
          out     (m/materialize-projection db (by-kind-name db :Projection "Refactor"))]
      (is (str/includes? out "Refactor the existing implementation") "the refactor context frames the output")
      (is (str/includes? out "Implement `extract`")
          "and the body is Blueprint's specs — composed with zero Refactor-specific renderers"))))

(deftest acts-chain-over-a-refined-focus
  (testing "focus → refine → materialize-over composes by plain threading — no named Tool"
    (let [db       (model*)
          stages   (lens/focus-nodes db '[(Operation ?n)])                       ; every Operation
          in-tc    (lens/refine db stages '[(in-module ?n "target-clojure")]) ; refined to one module
          rendered (m/materialize-over db "Blueprint" in-tc)]                 ; project the refined focus
      (is (set? stages) "focus-nodes / refine yield node-sets — the composable currency")
      (is (< (count in-tc) (count stages)) "refine narrowed the focus")
      (is (str/includes? rendered "Implement `extract`"))
      (is (not (str/includes? rendered "Implement `materialize-view`"))
          "the refine step bounded the focus to target-clojure — other modules' Operations excluded"))))

(deftest materialize-projection-reads-the-modelled-projection
  (testing "materialize-projection renders a modelled Projection through its OWN lens under its OWN name"
    (let [model    (model*)
          ;; a Projection whose name selects the Docs renderers and whose :through lens
          ;; carries a selection query (the manifest's :maps are intent, not dispatched).
          proj-db  (a/assemble-vars [#'mprd-stages #'mprd-Docs])
          db       (cs/union-dbs [model proj-db])
          out      (m/materialize-projection db (by-name db "Docs"))]
      (is (str/includes? out "### extract") "rendered under the projection's name (Docs)")
      (is (str/includes? out "**Grouping:** target-clojure")))))

(deftest probe-foci-compose-into-a-projection
  (testing "a probe's observation foci flow straight into a projection (the seam)"
    (let [db      (cs/build)
          finding (probes/run db "survey")              ; whole-model read → foci = all nodes by kind
          out     (m/materialize-finding db "Blueprint" finding)]
      (is (string? out) "the projection renders the finding's union focus")
      (is (str/includes? out "Implement")
          "Blueprint emits implementation specs for the focused Operations"))))
