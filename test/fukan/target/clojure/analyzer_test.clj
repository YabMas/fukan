(ns fukan.target.clojure.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.pipeline :as model-pipeline]))

(deftest analyzer-on-empty-model-passes
  (testing "an empty model with empty registry produces no changes"
    (let [m (analyzer/run (build/empty-model) (registry/make-registry) nil)]
      (is (map? m))
      ;; Empty model has no primitives, so no projects edges produced.
      (is (= [] (:edges m))))))

(deftest module-coord-extracted-from-canvas-stable-id
  (testing "canvas stable-id format (module/name with /) yields just the module-coord"
    ;; Gap 1: canvas stable-ids use '/' as the separator; the analyzer's
    ;; module-coord-of-primitive must extract the module-coord (the segment
    ;; before the first /), not pollute it with the full id.
    (let [model (-> (build/empty-model)
                    (build/add-primitive
                      (p/make-operation {:id "infra.server/start_server"
                                         :label "start_server"
                                         :parameters []})))
          reg (registry/make-registry)
          m1 (analyzer/run model reg "test/fixtures/clojure-projects/empty")
          ;; The projects edge has :to → artifact-ref whose id reflects
          ;; the derived ns/name. With root-prefix "" the expected
          ;; artifact id is "infra.server/start-server"
          projects-edge (first (filter #(= "infra.server/start_server" (-> % :from :id))
                                       (:edges m1)))
          to-ref (-> projects-edge :to :id)]
      (is (some? projects-edge) "projects edge exists")
      ;; Artifact ref is a tuple [:code/function "clojure" "<ns>/<name>"];
      ;; the last segment is the canonical address.
      (is (= "infra.server/start-server" (last to-ref))
          (str "expected canonical address 'infra.server/start-server' "
               "(module-coord = 'infra.server'), got: " (pr-str to-ref))))))

(deftest combined-pipeline-with-phase6-runs-cleanly
  (testing "fukan-on-fukan loads through all phases (4–6, canvas is sole spec source)"
    (let [m (model-pipeline/build-model "src")]
      (is (map? m))
      (is (contains? m :violations))
      (let [errors (filter #(= :error (:severity %)) (:violations m))]
        (is (empty? errors)
            (str "Phase 4/5/6 produced unexpected errors: "
                 (pr-str (mapv (juxt :phase :sub-phase :kind :message) errors))))))))
