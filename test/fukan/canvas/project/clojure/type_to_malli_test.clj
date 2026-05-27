(ns fukan.canvas.project.clojure.type-to-malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.core.shape :as shape]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Canvas-side ground truth — modelled after canvas/infra/server.clj's ServerOpts.
(def ^:private server-opts-element
  {:model-element-kind :Type
   :type-kind          :record
   :stable-id          "infra.server/type/ServerOpts"
   :entity-name        "ServerOpts"
   :module-coord       "infra.server"
   :doc                "HTTP server configuration."
   :fields             [['port (shape/parse '(optional :Integer))]]})

(deftest produces-valid-projection
  (let [p (core/project :clojure server-opts-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure server-opts-element {:registry registry})]
    (is (= :clojure/type-to-malli (:projection-kind p)))
    (is (= :Type                  (:model-element-kind p)))))

(deftest target-derivation
  (let [p (core/project :clojure server-opts-element {:registry registry})]
    (is (= "src/fukan/infra/server.clj" (-> p :target :path)))
    (is (= "fukan.infra.server"         (-> p :target :namespace)))
    (is (= "ServerOpts"                 (-> p :target :symbol)))))

(deftest template-renders-map-with-description
  (let [p (core/project :clojure server-opts-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(def ^:schema ServerOpts"))
    (is (str/includes? t "[:map {:description \"HTTP server configuration.\"}"))
    (is (str/includes? t "[:port {:optional true} :int]"))))

(deftest record-with-multiple-fields-and-compound-shapes
  (let [el {:model-element-kind :Type
            :type-kind          :record
            :stable-id          "distributed.cluster/type/Cluster"
            :entity-name        "Cluster"
            :module-coord       "distributed.cluster"
            :doc                "The cluster's view from one node."
            :fields             [['self           (shape/parse :NodeId)]
                                 ['members        (shape/parse '(set-of :NodeId))]
                                 ['current_term   (shape/parse :Term)]
                                 ['current_leader (shape/parse '(optional :NodeId))]]}
        p (core/project :clojure el {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:self :NodeId]"))
    (is (str/includes? t "[:members [:set :NodeId]]"))
    (is (str/includes? t "[:current_term :Term]"))
    (is (str/includes? t "[:current_leader {:optional true} :NodeId]"))))

(deftest empty-doc-omits-description
  (let [el (assoc server-opts-element :doc nil)
        p  (core/project :clojure el {:registry registry})
        t  (:template p)]
    (is (str/includes? t "[:map"))
    (is (not (str/includes? t ":description")))))

(deftest leaf-aliases-applied
  (testing "Integer → :int, String → :string"
    (let [el {:model-element-kind :Type
              :type-kind          :record
              :stable-id          "infra.server/type/ServerInfo"
              :entity-name        "ServerInfo"
              :module-coord       "infra.server"
              :doc                "Running server information."
              :fields             [['port (shape/parse :Integer)]
                                   ['note (shape/parse :String)]]}
          p (core/project :clojure el {:registry registry})
          t (:template p)]
      (is (str/includes? t "[:port :int]"))
      (is (str/includes? t "[:note :string]")))))

(deftest prose-survives
  (let [p (core/project :clojure server-opts-element {:registry registry})]
    (is (= "HTTP server configuration." (:prose p)))))

(deftest context-carries-source-ref
  (let [p (core/project :clojure server-opts-element {:registry registry})]
    (is (= "canvas/infra/server.clj" (-> p :context :canvas-source-ref)))
    (is (= :canvas/record-doc        (-> p :context :doc-source)))
    (is (= 1                         (-> p :context :field-count)))))
