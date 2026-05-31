(ns fukan.canvas.project.clojure.rule-to-predicate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/validation/rules-4a :: AtMostOneCompositeParent (rule).
(def ^:private rule-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/rule
   :stable-id          "validation.rules-4a/rule/AtMostOneCompositeParent"
   :entity-name        "AtMostOneCompositeParent"
   :module-coord       "validation.rules-4a"
   :doc                "Check that every module-Container belongs to at most one composite parent."
   ;; The canvas `(when AtMostOneCompositeParent (model :model/Model))` parses
   ;; to a vec of [trigger-name (param :Type) …].
   :when-vec           ['AtMostOneCompositeParent '(model :model/Model)]})

(deftest produces-valid-projection
  (let [p (core/project :clojure rule-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure rule-element {:registry registry})]
    (is (= :clojure/rule-to-predicate (:projection-kind p)))
    (is (= :Affordance                (:model-element-kind p)))))

(deftest target-derivation-uses-kebab-rule-name
  (let [p (core/project :clojure rule-element {:registry registry})]
    (is (= "fukan.validation.rules-4a"  (-> p :target :namespace)))
    (is (= "at-most-one-composite-parent" (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure rule-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn at-most-one-composite-parent"))
    (is (str/includes? t "[model]"))
    (is (str/includes? t "(throw (ex-info"))))

(deftest exception-payload-includes-rule-name-and-when
  (let [p (core/project :clojure rule-element {:registry registry})
        t (:template p)]
    (is (str/includes? t ":canvas-id \"validation.rules-4a/rule/AtMostOneCompositeParent\""))
    (is (str/includes? t ":rule-name \"AtMostOneCompositeParent\""))
    (is (str/includes? t ":when (quote"))
    (is (str/includes? t "AtMostOneCompositeParent"))))

(deftest prose-envelope-frames-reactive
  (let [p (core/project :clojure rule-element {:registry registry})
        pr (:prose p)]
    (is (str/starts-with? pr "Reactive rule: AtMostOneCompositeParent."))
    (is (str/includes? pr "Trigger:"))
    (is (str/includes? pr "trigger fires"))))

(deftest empty-when-vec-still-produces-valid-projection
  (testing "rule with no when (degenerate authoring) projects safely"
    (let [el (assoc rule-element :when-vec [])
          p  (core/project :clojure el {:registry registry})
          t  (:template p)]
      (is (core/valid-projection? p))
      (is (not (str/includes? t ":when (quote")))
      (is (not (str/includes? (:prose p) "Trigger:"))))))

(deftest context-carries-rule-name-and-when
  (let [p (core/project :clojure rule-element {:registry registry})]
    (is (= "AtMostOneCompositeParent" (-> p :context :rule-name)))
    (is (= "canvas/validation/rules-4a.clj"
           (-> p :context :canvas-source-ref)))
    (is (string? (-> p :context :when)))
    (is (str/includes? (-> p :context :when) "AtMostOneCompositeParent"))))
