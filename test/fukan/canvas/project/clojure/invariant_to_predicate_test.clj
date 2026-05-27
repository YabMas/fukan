(ns fukan.canvas.project.clojure.invariant-to-predicate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/validation/rules-4a :: AtMostOneCompositeParent.
(def ^:private at-most-one-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/invariant
   :stable-id          "validation.rules-4a/invariant/AtMostOneCompositeParent"
   :entity-name        "AtMostOneCompositeParent"
   :module-coord       "validation.rules-4a"
   :doc                "A module-Container belongs to at most one composite parent. A Violation of kind :4a/multiple-composite-parents (error) is emitted for every module appearing in two or more composites' :children sets."
   :holds-that         "module-has-at-most-one-composite-parent"})

(deftest produces-valid-projection
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= :clojure/invariant-to-predicate (:projection-kind p)))
    (is (= :Affordance                     (:model-element-kind p)))))

(deftest target-derivation-uses-holds-that-as-symbol
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= "fukan.validation.rules-4a" (-> p :target :namespace)))
    (is (= "module-has-at-most-one-composite-parent"
           (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn module-has-at-most-one-composite-parent"))
    (is (str/includes? t "[model]"))
    (is (str/includes? t "(throw (ex-info"))))

(deftest exception-payload-carries-canvas-id-name-and-holds-that
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        t (:template p)]
    (is (str/includes? t ":canvas-id \"validation.rules-4a/invariant/AtMostOneCompositeParent\""))
    (is (str/includes? t ":invariant-name \"AtMostOneCompositeParent\""))
    (is (str/includes? t ":holds-that \"module-has-at-most-one-composite-parent\""))))

(deftest prose-envelope-frames-invariant
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        pr (:prose p)]
    (is (str/starts-with? pr "Invariant: AtMostOneCompositeParent."))
    (is (str/includes? pr "What must hold: module-has-at-most-one-composite-parent."))
    (is (str/includes? pr "Property-check approach:"))))

(deftest holds-that-absent-falls-back-to-invariant-name
  (testing "missing holds-that triggers addr/canonical's :invariant-name fallback"
    (let [el (assoc at-most-one-element :holds-that nil)
          p  (core/project :clojure el {:registry registry})]
      (is (= "at-most-one-composite-parent" (-> p :target :symbol)))
      (is (str/includes? (:template p)
                         "(defn at-most-one-composite-parent")))))

(deftest holds-that-blank-string-also-falls-back
  (let [el (assoc at-most-one-element :holds-that "")
        p  (core/project :clojure el {:registry registry})]
    (is (= "at-most-one-composite-parent" (-> p :target :symbol)))))

(deftest context-carries-invariant-name-and-holds-that
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= "AtMostOneCompositeParent" (-> p :context :invariant-name)))
    (is (= "module-has-at-most-one-composite-parent"
           (-> p :context :holds-that)))
    (is (= "canvas/validation/rules-4a.clj"
           (-> p :context :canvas-source-ref)))))
