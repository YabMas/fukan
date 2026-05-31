(ns fukan.canvas.project.clojure.checker-to-defn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.core.shape :as shape]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/validation/rules_4a.clj :: check.
;; The validation vocab bakes in `(Model) -> (list-of Violation)`;
;; affordance-element exposes that as inputs/outputs parsed shapes.
(def ^:private check-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/checker
   :stable-id          "validation.rules-4a/check"
   :entity-name        "check"
   :module-coord       "validation.rules-4a"
   :doc                "Run all five 4a composition rules against the Model."
   :inputs             [["model" (shape/parse :model/Model)]]
   :outputs            (shape/parse '(list-of :agent/Violation))})

(deftest produces-valid-projection
  (let [p (core/project :clojure check-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure check-element {:registry registry})]
    (is (= :clojure/checker-to-defn (:projection-kind p)))
    (is (= :Affordance              (:model-element-kind p)))))

(deftest target-derivation-kebab-cases
  (let [p (core/project :clojure check-element {:registry registry})]
    (is (= "src/fukan/validation/rules_4a.clj" (-> p :target :path)))
    (is (= "fukan.validation.rules-4a"         (-> p :target :namespace)))
    (is (= "check"                             (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure check-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn check"))
    (is (str/includes? t "[model]"))
    (is (str/includes? t ":malli/schema"))))

(deftest malli-schema-single-arg-violation-list
  (let [p (core/project :clojure check-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:=> [:cat :model/Model] [:sequential :agent/Violation]]"))))

(deftest exception-stub-carries-canvas-id
  (let [p (core/project :clojure check-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(throw (ex-info \"check: not yet implemented\""))
    (is (str/includes? t ":canvas-id \"validation.rules-4a/check\""))))

(deftest context-flags-validation-entry
  (let [p (core/project :clojure check-element {:registry registry})]
    (is (true? (-> p :context :validation-entry?)))
    (is (= 1   (-> p :context :arity)))
    (is (= :canvas/checker-doc (-> p :context :doc-source)))))

(deftest defensive-fallback-when-inputs-omitted
  (testing "if caller forgot inputs/outputs the projection still emits the baked-in shape"
    (let [el (dissoc check-element :inputs :outputs)
          p  (core/project :clojure el {:registry registry})
          t  (:template p)]
      (is (str/includes? t "[model]"))
      (is (str/includes? t "[:=> [:cat :model/Model] [:sequential :agent/Violation]]")))))
