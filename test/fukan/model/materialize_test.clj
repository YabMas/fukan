(ns fukan.model.materialize-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.model.materialize :as m]
            [fukan.model.pipeline :as pipeline]))

;; materialize is the pure LOWER direction — it projects the design model alone,
;; with no extraction needed (build-model nil = design-only).

(deftest materializes-a-stage-into-an-implementation-spec
  (testing "a modelled Stage projects to its implementation spec (signature + intent)"
    (let [db (pipeline/build-model nil)]
      (is (= {:name    "check"
              :module  "core.structure"
              :params  [{:label "db" :shape "StructureDb"}]
              :returns "[Violation]"
              :effects []
              :calls   []}
             (dissoc (m/materialize-stage db "check") :doc))
          "check : (db: StructureDb) → [Violation]")
      (is (= {:name    "extract"
              :module  "target.clojure"
              :params  [{:label "paths" :shape "[Path]"}]
              :returns "StructureDb"
              :effects ["io"]
              :calls   ["analyze"]}
             (dissoc (m/materialize-stage db "extract") :doc))
          "extract : (paths: [Path]) → StructureDb, performs io, calls analyze"))))

(deftest renders-a-prose-implementation-instruction
  (testing "the spec renders to an actionable instruction"
    (let [db   (pipeline/build-model nil)
          text (m/instruction (m/materialize-stage db "extract"))]
      (is (str/includes? text "Implement `extract` in module `target.clojure`"))
      (is (str/includes? text "paths: [Path]"))
      (is (str/includes? text "→ StructureDb"))
      (is (str/includes? text "Effects: io"))
      (is (str/includes? text "Calls: analyze")))))

(deftest unknown-stage-materializes-to-nil
  (testing "materializing a non-existent Stage yields nil (no spec to project)"
    (is (nil? (m/materialize-stage (pipeline/build-model nil) "no-such-stage")))))
