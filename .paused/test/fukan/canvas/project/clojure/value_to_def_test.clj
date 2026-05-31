(ns fukan.canvas.project.clojure.value-to-def-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            ;; Loader: pulls in the defmethod under test.
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

(def ^:private nodeid-element
  {:model-element-kind :Type
   :type-kind          :atomic
   :stable-id          "distributed.cluster/type/NodeId"
   :entity-name        "NodeId"
   :module-coord       "distributed.cluster"
   :doc                "An opaque, stable identity for a cluster member. Distinct from the transport-layer address; survives restarts and rebinds."})

(deftest produces-valid-projection
  (let [p (core/project :clojure nodeid-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure nodeid-element {:registry registry})]
    (is (= :clojure/value-to-def (:projection-kind p)))
    (is (= :clojure              (:lens-id p)))
    (is (= :Type                 (:model-element-kind p)))))

(deftest target-derivation
  (let [p (core/project :clojure nodeid-element {:registry registry})]
    (is (= "src/fukan/distributed/cluster.clj" (-> p :target :path)))
    (is (= "fukan.distributed.cluster"         (-> p :target :namespace)))
    (is (= "NodeId"                            (-> p :target :symbol)))))

(deftest template-includes-name-and-doc
  (let [p (core/project :clojure nodeid-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(def ^:schema NodeId"))
    (is (str/includes? t "[:any {:description"))
    (is (str/includes? t "opaque, stable identity"))))

(deftest doc-missing-renders-bare-any
  (testing "no doc → schema collapses to [:any] without :description"
    (let [el (dissoc nodeid-element :doc)
          p  (core/project :clojure el {:registry registry})
          t  (:template p)]
      (is (str/includes? t "[:any]"))
      (is (not (str/includes? t ":description"))))))

(deftest prose-survives-as-self-contained-intent
  (let [p (core/project :clojure nodeid-element {:registry registry})]
    (is (str/includes? (:prose p) "opaque, stable identity"))))

(deftest context-carries-source-ref
  (let [p (core/project :clojure nodeid-element {:registry registry})]
    (is (= "canvas/distributed/cluster.clj"
           (-> p :context :canvas-source-ref)))
    (is (= :canvas/value-doc (-> p :context :doc-source)))))

(deftest project-layer-module-segment-uses-hyphens
  (testing "project_layer.registry kebab-cases per address-derivation convention"
    (let [el {:model-element-kind :Type
              :type-kind          :atomic
              :stable-id          "project_layer.registry/type/Registry"
              :entity-name        "Registry"
              :module-coord       "project_layer.registry"
              :doc                "Registry."}
          p (core/project :clojure el {:registry registry})]
      (is (= "fukan.project-layer.registry" (-> p :target :namespace)))
      (is (= "src/fukan/project_layer/registry.clj" (-> p :target :path))))))
