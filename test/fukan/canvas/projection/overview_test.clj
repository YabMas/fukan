(ns fukan.canvas.projection.overview-test
  "The system overview derives its faculty→module map from Module roles (the manifest collapse)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.projection.overview :as ov]))

(deftest overview-derives-realizers-from-module-roles
  (testing "the rendered overview names each faculty's realizing modules, derived from :realizes"
    (let [out (ov/system-overview (pipeline/build-model nil))]
      (is (str/includes? out "core-structure")    "Model ⟶ core-structure")
      (is (str/includes? out "canvas-source, target-clojure") "Source ⟶ both ingestion modules")
      (is (str/includes? out "probes, target-correspondence") "Lens ⟶ both reading modules")
      (is (str/includes? out "materialize")        "Projection ⟶ materialize"))))
