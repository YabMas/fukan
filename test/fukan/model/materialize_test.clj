(ns fukan.model.materialize-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [canvas.vocab.lens :refer [Lens]]
            [canvas.vocab.projection :refer [Projection]]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.model.materialize :as m]
            [fukan.model.pipeline :as pipeline]
            ;; loaded so the vocab-derived rules carry an Operation kind-rule (coverage /
            ;; drift lenses reference it) and the drift lens's correspondence predicate
            ;; resolves
            [fukan.target.clojure :as target]
            [fukan.target.correspondence :as corr]))

;; materialize is the pure LOWER direction — it projects the design model alone
;; (build-model nil = design-only, no extraction).

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(defn- by-kind-name [db kind n]
  (ffirst (d/q '[:find ?e :in $ ?k ?n :where [?e :structure/of ?k] [?e :entity/name ?n]] db kind n)))

(deftest render-dispatches-per-projection-and-kind-and-composes-references
  (testing "render [Blueprint :Stage] composes its :Shape renders (via dispatch) into the signature"
    (let [db   (pipeline/build-model nil)
          text (m/render db "Blueprint" (by-name db "extract"))]
      (is (str/includes? text "Implement `extract` in module `target.clojure`"))
      (is (str/includes? text "paths: [Path]") "the :in Shape rendered via render :Shape (list → [Kind])")
      (is (str/includes? text "→ StructureDb"))
      (is (str/includes? text "Effects: io"))
      (is (str/includes? text "Calls: analyze")))))

(deftest the-same-node-renders-differently-per-projection
  (testing "Blueprint and Docs are distinct targets over the same focus — the generalization"
    (let [db        (pipeline/build-model nil)
          extract   (by-name db "extract")
          blueprint (m/render db "Blueprint" extract)
          docs      (m/render db "Docs" extract)]
      (is (str/includes? blueprint "Implement `extract`") "Blueprint → an implementation spec")
      (is (str/includes? docs "### extract") "Docs → a reference doc section")
      (is (str/includes? docs "**Module:** target.clojure"))
      (is (str/includes? docs "**Takes:** paths") "shapes still compose via dispatch under Docs")
      (is (not= blueprint docs) "same node, different artifact per projection"))))

(deftest materialize-view-composes-a-lens-focus-under-blueprint
  (testing "materialize-view renders each primitive a lens selects, under Blueprint (the default)"
    (let [model   (pipeline/build-model nil)
          lens-db (s/with-structures
                    (s/within-module "v"
                      (Lens "target" (focus "the target extractor's stages"
                                            '[(Stage ?n) (in-module ?n "target.clojure")]))))
          db      (cs/merge-dbs [model lens-db])
          view    (m/materialize-view db (by-name db "target"))]
      (is (str/includes? view "Implement `analyze`"))
      (is (str/includes? view "Implement `extract`"))
      (is (str/includes? view "paths: [Path]")))))

(deftest empty-focus-materializes-to-empty-string
  (testing "a lens whose focus is empty composes to nothing"
    (let [model   (pipeline/build-model nil)            ; loads the canvas vocab (the (Stage …) rule)
          lens-db (s/with-structures
                    (s/within-module "v"
                      (Lens "none" (focus "nothing" '[(Stage ?n) (in-module ?n "no-such-module")]))))
          db      (cs/merge-dbs [model lens-db])]
      (is (= "" (m/materialize-view db (by-name db "none")))))))

(deftest materialize-module-composes-a-modules-stages
  (testing "materialize-module renders a module's Stages under a projection — no stored lens"
    (let [db (pipeline/build-model nil)]
      (let [bp (m/materialize-module db "Blueprint" "target.clojure")]
        (is (str/includes? bp "Implement `analyze`"))
        (is (str/includes? bp "Implement `extract`"))
        (is (str/includes? bp "paths: [Path]") "shapes rendered inline"))
      (let [docs (m/materialize-module db "Docs" "target.clojure")]
        (is (str/includes? docs "### analyze"))
        (is (str/includes? docs "### extract")))
      (is (= "" (m/materialize-module db "Blueprint" "no-such-module")) "unknown module → empty"))))

(deftest materialize-focus-takes-ad-hoc-clauses
  (testing "materialize-focus renders the nodes an ad-hoc :where clause selects, under a projection"
    (let [db   (pipeline/build-model nil)
          spec (m/materialize-focus db "Blueprint" '[(Stage ?n) (in-module ?n "materialize")])]
      (is (str/includes? spec "Implement `materialize-view`")
          "the materialize module's own modelled Stage is projected"))))

(deftest shipped-lenses-with-queries-are-evaluable
  (testing "every retrofitted self-model lens resolves to a node-set (no prose-only throw)"
    (let [model (pipeline/build-model nil)
          code  (target/extract "test/fixtures/target/sample.clj")   ; Operations, so coverage/drift resolve
          db    (cs/merge-dbs [model code])]
      ;; the predicate the drift lens query invokes
      (is (corr/module-corresponds? "target.clojure" "fukan.target.clojure"))
      (doseq [ln ["survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"]]
        (let [focus (lens/evaluate-lens db (by-kind-name db :Lens ln))]
          (is (set? focus) (str "lens " ln " evaluates to a node-set"))))
      (is (seq (lens/evaluate-lens db (by-kind-name db :Lens "survey")))
          "survey (whole model) is non-empty")
      (is (seq (lens/evaluate-lens db (by-kind-name db :Lens "coverage")))
          "coverage selects the extracted Operations"))))

(deftest materialize-projection-runs-the-shipped-blueprint
  (testing "Blueprint (through the now-query-bearing survey lens) materializes the model's Stages"
    (let [db  (pipeline/build-model nil)
          out (m/materialize-projection db (by-kind-name db :Projection "Blueprint"))]
      ;; survey selects the whole model; compose renders only the kinds Blueprint covers
      ;; (named Stages), not every node as a bare name
      (is (str/includes? out "Implement `extract`"))
      (is (str/includes? out "Implement `check`") "Stages from across modules are projected")
      (is (> (count (re-seq #"This is an implementation specification" out)) 3)
          "several Stages projected; bare Kinds/Concepts are not rendered standalone"))))

(deftest driftclose-contextualizes-blueprint-not-duplicates-it
  (testing "DriftClose composes Blueprint — its specs framed by a drift context, not a parallel renderer"
    ;; design-only model: no extracted code → every modelled Stage drifts → the drift
    ;; lens selects them all → DriftClose = Blueprint's specs under a drift-closing context.
    (let [db  (pipeline/build-model nil)
          out (m/materialize-projection db (by-kind-name db :Projection "DriftClose"))]
      (is (str/includes? out "modelled but have no realizing function") "the drift context preamble")
      (is (str/includes? out "Implement `extract` in module `target.clojure`")
          "the body IS Blueprint's spec — composed, not a bespoke close-instruction")
      ;; delegation: a single node under DriftClose renders identically to under Blueprint
      ;; (no DriftClose-specific renderer — the context is applied only at the view level)
      (let [extract (by-kind-name db :Stage "extract")]
        (is (= (m/render db "Blueprint" extract) (m/render db "DriftClose" extract))
            "DriftClose delegates per-node rendering to its base Blueprint")))))

(deftest a-context-composes-over-any-base
  (testing "the same mechanism composes Blueprint with an arbitrary context — e.g. a refactor framing"
    (let [model   (pipeline/build-model nil)
          ;; an ad-hoc contextualization of the SHIPPED Blueprint — no Refactor renderer exists
          proj-db (s/with-structures
                    (s/within-module "v"
                      (Lens "stages" (focus "x" '[(Stage ?n) (in-module ?n "target.clojure")]))
                      (Projection "Refactor"
                        (through stages)
                        (contextualizes (across "projection" "Blueprint"))
                        (context "Refactor the existing implementation to match these specs:"))))
          db      (cs/resolve-cross-refs (cs/merge-dbs [model proj-db]))
          out     (m/materialize-projection db (by-kind-name db :Projection "Refactor"))]
      (is (str/includes? out "Refactor the existing implementation") "the refactor context frames the output")
      (is (str/includes? out "Implement `extract`")
          "and the body is Blueprint's specs — composed with zero Refactor-specific renderers"))))

(deftest acts-chain-over-a-refined-focus
  (testing "focus → refine → materialize-over composes by plain threading — no named Tool"
    (let [db       (pipeline/build-model nil)
          stages   (lens/focus-nodes db '[(Stage ?n)])                       ; every Stage
          in-tc    (lens/refine db stages '[(in-module ?n "target.clojure")]) ; refined to one module
          rendered (m/materialize-over db "Blueprint" in-tc)]                 ; project the refined focus
      (is (set? stages) "focus-nodes / refine yield node-sets — the composable currency")
      (is (< (count in-tc) (count stages)) "refine narrowed the focus")
      (is (str/includes? rendered "Implement `analyze`"))
      (is (str/includes? rendered "Implement `extract`"))
      (is (not (str/includes? rendered "Implement `materialize-view`"))
          "the refine step bounded the focus to target.clojure — other modules' Stages excluded"))))

(deftest materialize-projection-reads-the-modelled-projection
  (testing "materialize-projection renders a modelled Projection through its OWN lens under its OWN name"
    (let [model    (pipeline/build-model nil)
          ;; a Projection whose name selects the Docs renderers and whose :through lens
          ;; carries a selection query (the manifest's :maps are intent, not dispatched).
          proj-db  (s/with-structures
                     (s/within-module "v"
                       (Lens "stages" (focus "target stages"
                                             '[(Stage ?n) (in-module ?n "target.clojure")]))
                       (Projection "Docs"
                         (through stages)
                         (maps (Mapping (from "a function") (to "a doc section"))))))
          db       (cs/merge-dbs [model proj-db])
          out      (m/materialize-projection db (by-name db "Docs"))]
      (is (str/includes? out "### analyze") "rendered under the projection's name (Docs)")
      (is (str/includes? out "### extract"))
      (is (str/includes? out "**Module:** target.clojure")))))
