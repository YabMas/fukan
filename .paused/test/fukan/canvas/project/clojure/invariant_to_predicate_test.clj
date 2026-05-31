(ns fukan.canvas.project.clojure.invariant-to-predicate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/validation/rules-4a :: AtMostOneCompositeParent.
;; Phase 7.5 Sprint 2: holds-that is now prose (not a kebab-case slug). The
;; symbol is derived from :entity-name; holds-that lands in the docstring.
;;
;; Phase 8 Sprint 5: this projection is now the opt-in path; the element
;; explicitly carries :canvas-projection-kind :predicate so the augmented
;; dispatch-key-of routes through :canvas/invariant rather than the
;; default :canvas/invariant+property-test synthetic key. The default
;; behavior (property-test) is covered by the
;; `invariant-to-property-test` test ns.
(def ^:private at-most-one-element
  {:model-element-kind     :Affordance
   :canvas-role            :canvas/invariant
   :canvas-projection-kind :predicate
   :stable-id              "validation.rules-4a/invariant/AtMostOneCompositeParent"
   :entity-name            "AtMostOneCompositeParent"
   :module-coord           "validation.rules-4a"
   :doc                    "A module-Container belongs to at most one composite parent. A Violation of kind :4a/multiple-composite-parents (error) is emitted for every module appearing in two or more composites' :children sets."
   :holds-that             "a module-Container belongs to at most one composite parent"})

(deftest produces-valid-projection
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= :clojure/invariant-to-predicate (:projection-kind p)))
    (is (= :Affordance                     (:model-element-kind p)))))

(deftest target-derivation-uses-entity-name-as-symbol
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= "fukan.validation.rules-4a" (-> p :target :namespace)))
    (is (= "at-most-one-composite-parent"
           (-> p :target :symbol))
        "symbol is kebab(entity-name), never the prose holds-that clause")))

(deftest template-defn-shape
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn at-most-one-composite-parent"))
    (is (str/includes? t "[model]"))
    (is (str/includes? t "(throw (ex-info"))))

(deftest exception-payload-carries-canvas-id-name-and-holds-that
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        t (:template p)]
    (is (str/includes? t ":canvas-id \"validation.rules-4a/invariant/AtMostOneCompositeParent\""))
    (is (str/includes? t ":invariant-name \"AtMostOneCompositeParent\""))
    (is (str/includes? t ":holds-that \"a module-Container belongs to at most one composite parent\""))))

(deftest prose-envelope-frames-invariant
  (let [p (core/project :clojure at-most-one-element {:registry registry})
        pr (:prose p)]
    (is (str/starts-with? pr "Invariant: AtMostOneCompositeParent."))
    (is (str/includes? pr "What must hold: a module-Container belongs to at most one composite parent."))
    (is (str/includes? pr "Property-check approach:"))))

(deftest holds-that-absent-still-produces-valid-symbol
  (testing "missing/blank holds-that does not affect the symbol (entity-name path)"
    (doseq [val [nil ""]]
      (let [el (assoc at-most-one-element :holds-that val)
            p  (core/project :clojure el {:registry registry})]
        (is (= "at-most-one-composite-parent" (-> p :target :symbol)))
        (is (str/includes? (:template p)
                           "(defn at-most-one-composite-parent"))))))

(deftest predicate-projection-requires-explicit-opt-out
  ;; Phase 8 Sprint 5 — Option β. Without the
  ;; :canvas-projection-kind :predicate override, the default
  ;; dispatch-key-of routes invariants to the property-test projection.
  ;; This test pins the opt-out contract: omitting the override flips
  ;; routing to the test-side projection.
  (testing "invariant element WITHOUT :canvas-projection-kind override → property-test"
    (let [el (dissoc at-most-one-element :canvas-projection-kind)
          p  (core/project :clojure el {:registry registry})]
      (is (= :clojure/invariant-to-property-test (:projection-kind p))
          "no override = default = property-test projection")))
  (testing "invariant element WITH :canvas-projection-kind :predicate → predicate"
    (let [p (core/project :clojure at-most-one-element {:registry registry})]
      (is (= :clojure/invariant-to-predicate (:projection-kind p))
          "explicit :predicate override routes back to the predicate projection"))))

(deftest context-carries-invariant-name-and-holds-that
  (let [p (core/project :clojure at-most-one-element {:registry registry})]
    (is (= "AtMostOneCompositeParent" (-> p :context :invariant-name)))
    (is (= "a module-Container belongs to at most one composite parent"
           (-> p :context :holds-that)))
    (is (= "canvas/validation/rules-4a.clj"
           (-> p :context :canvas-source-ref)))))
