(ns fukan.canvas.projection.architecture-test
  "The architecture view renders fukan's subsystems, their modules, and the :may-depend DAG."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.projection.architecture :as arch]))

(deftest architecture-overview-renders-subsystems-and-dag
  (testing "each subsystem lists its modules and its declared dependencies"
    (let [out (arch/architecture-overview (pipeline/build-cozo-model nil))]
      (is (str/includes? out "kernel"))
      (is (str/includes? out "core-structure"))
      (is (str/includes? out "orchestration"))
      (is (str/includes? out "model-pipeline, infra-model"))
      (is (str/includes? out "kernel, ingestion") "orchestration declares may-depend kernel + ingestion"))))
