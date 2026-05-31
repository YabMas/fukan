(ns fukan.canvas.project.clojure.getter-to-defn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.core.shape :as shape]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/distributed/cluster.clj :: get_self_role.
;; Lifecycle vocab wraps the inner shape (`:NodeRole`) in :optional —
;; the element-builder hands us the wrapped outputs shape.
(def ^:private get-self-role-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/getter
   :stable-id          "distributed.cluster/get_self_role"
   :entity-name        "get_self_role"
   :module-coord       "distributed.cluster"
   :doc                "This node's current role within the cluster."
   :inputs             []
   :outputs            (shape/parse '(optional :NodeRole))})

(deftest produces-valid-projection
  (let [p (core/project :clojure get-self-role-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure get-self-role-element {:registry registry})]
    (is (= :clojure/getter-to-defn (:projection-kind p)))
    (is (= :Affordance             (:model-element-kind p)))))

(deftest target-derivation-kebab-cases
  (let [p (core/project :clojure get-self-role-element {:registry registry})]
    (is (= "src/fukan/distributed/cluster.clj" (-> p :target :path)))
    (is (= "fukan.distributed.cluster"         (-> p :target :namespace)))
    (is (= "get-self-role"                     (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure get-self-role-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn get-self-role"))
    (is (str/includes? t "\"This node's current role within the cluster.\""))
    (is (str/includes? t "[]"))
    (is (str/includes? t ":malli/schema"))))

(deftest malli-schema-zero-arg-maybe-return
  (let [p (core/project :clojure get-self-role-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:=> [:cat] [:maybe :NodeRole]]"))))

(deftest exception-stub-carries-canvas-id
  (let [p (core/project :clojure get-self-role-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(throw (ex-info \"get-self-role: not yet implemented\""))
    (is (str/includes? t ":canvas-id \"distributed.cluster/get_self_role\""))))

(deftest context-flags-optional-return
  (let [p (core/project :clojure get-self-role-element {:registry registry})]
    (is (true? (-> p :context :optional-return?)))
    (is (= 0   (-> p :context :arity)))
    (is (= :canvas/getter-doc (-> p :context :doc-source)))))

(deftest bare-inner-shape-still-wraps-in-maybe
  (testing "if outputs already arrives unwrapped, the projection still emits [:maybe T]"
    (let [el (assoc get-self-role-element :outputs (shape/parse :NodeRole))
          p  (core/project :clojure el {:registry registry})
          t  (:template p)]
      (is (str/includes? t "[:=> [:cat] [:maybe :NodeRole]]")))))
