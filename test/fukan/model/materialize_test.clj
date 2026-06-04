(ns fukan.model.materialize-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [canvas.vocab.lens :refer [Lens]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.model.materialize :as m]
            [fukan.model.pipeline :as pipeline]))

;; materialize is the pure LOWER direction — it projects the design model alone
;; (build-model nil = design-only, no extraction).

(defn- by-name [db n]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db n)))

(deftest render-dispatches-per-kind-and-composes-references
  (testing "render :Stage composes its :Shape renders (via dispatch) into the signature"
    (let [db   (pipeline/build-model nil)
          text (m/render db (by-name db "extract"))]
      (is (str/includes? text "Implement `extract` in module `target.clojure`"))
      (is (str/includes? text "paths: [Path]") "the :in Shape rendered via render :Shape (list → [Kind])")
      (is (str/includes? text "→ StructureDb"))
      (is (str/includes? text "Effects: io"))
      (is (str/includes? text "Calls: analyze")))))

(deftest materialize-view-composes-a-lens-focus
  (testing "materialize-view renders each primitive a lens selects — the focus area's specs"
    (let [model   (pipeline/build-model nil)
          lens-db (s/with-structures
                    (s/within-module "v"
                      (Lens "target" (focus "the target extractor's stages"
                                            '[(Stage ?n) (in-module ?n "target.clojure")]))))
          db      (cs/merge-dbs [model lens-db])
          view    (m/materialize-view db (by-name db "target"))]
      ;; the lens selects target.clojure's stages (analyze, extract) → both appear
      (is (str/includes? view "Implement `analyze`"))
      (is (str/includes? view "Implement `extract`"))
      ;; composition along references is visible: shapes rendered inline
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
  (testing "materialize-module (the live by-module entry) renders a module's Stages — no stored lens"
    (let [db   (pipeline/build-model nil)
          spec (m/materialize-module db "target.clojure")]
      (is (str/includes? spec "Implement `analyze`"))
      (is (str/includes? spec "Implement `extract`"))
      (is (str/includes? spec "paths: [Path]") "shapes rendered inline"))
    (testing "an unknown module composes to the empty string"
      (let [db (pipeline/build-model nil)]
        (is (= "" (m/materialize-module db "no-such-module")))))))

(deftest materialize-focus-takes-ad-hoc-clauses
  (testing "materialize-focus renders the nodes an ad-hoc :where clause selects"
    (let [db   (pipeline/build-model nil)
          spec (m/materialize-focus db '[(Stage ?n) (in-module ?n "materialize")])]
      (is (str/includes? spec "Implement `materialize-view`")
          "the materialize module's own modelled Stage is projected"))))
