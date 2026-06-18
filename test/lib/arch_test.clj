(ns lib.arch-test
  "The opt-in clean-architecture quality layer: no two modules mutually depend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [lib.code :as code]
            [lib.arch]))

(defn- law-desc [substr]
  (->> (s/structure-by-tag :lib.arch/ModuleArchitecture) :laws
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (s/check db) (filter #(= desc (:law %)))
         (mapcat :offenders) (map first) (map #(:entity/name (d/entity db %))) set)))

;; a synthetic mutual pair: A's op delegates to B's op and B's op delegates to A's op
(declare t-mb-op)
(code/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(code/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(code/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(code/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest mutual-dependency-fires
  (testing "two modules whose ops mutually delegate violate the no-mutual-dependency law"
    (let [db (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (offenders db "mutually depend"))))))

(deftest fukan-module-graph-has-no-mutual-dependency
  (testing "fukan's own module graph is acyclic — the quality law is a green opt-in"
    (is (empty? (offenders (pipeline/build-model nil) "mutually depend")))))

;; ── conformance fixtures: S's op delegates to T's op (cross-subsystem) ──
(code/Operation ^{:name "op-t"} t-op-t "callee in T")
(code/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(code/Module ^{:name "M-S"} t-cm-s {:exposes [t-op-s]})
(code/Module ^{:name "M-T"} t-cm-t {:exposes [t-op-t]})
(declare t-sub-T)
(code/Subsystem ^{:name "S-ok"}  t-sub-S-ok  {:child [t-cm-s] :may-depend [t-sub-T]})  ; declares the dep
(code/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})                          ; does NOT
(code/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-green-when-cross-dep-is-declared
  (testing "M-S → M-T conforms because subsystem S-ok declares :may-depend T"
    (let [db (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-ok #'t-sub-T])]
      (is (empty? (offenders db "cross-subsystem"))))))

(deftest conformance-fires-on-undeclared-cross-dep
  (testing "M-S → M-T violates because S-bad does NOT declare :may-depend T"
    (let [db (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])]
      (is (= #{"M-S"} (offenders db "cross-subsystem"))))))

;; ── acyclicity fixtures: a 2-cycle in :may-depend ──
(declare t-sub-cy-b)
(code/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(code/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest may-depend-acyclicity-fires-on-a-cycle
  (testing "a :may-depend cycle CY-A ⇄ CY-B violates the acyclicity law"
    (let [db (a/assemble-vars [#'t-sub-cy-a #'t-sub-cy-b])]
      (is (= #{"CY-A" "CY-B"} (offenders db "acyclic"))))))

(deftest fukan-may-depend-graph-is-acyclic
  (testing "fukan's declared :may-depend DAG is acyclic"
    (is (empty? (offenders (pipeline/build-model nil) "acyclic")))))

;; ── membership fixtures: a module in no subsystem (with a subsystem present) ──
(code/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(code/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(code/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-fires-on-unclustered-module
  (testing "with a Subsystem present, a Module in none is an offender"
    (let [db (a/assemble-vars [#'t-orphan #'t-homed #'t-sub-home])]
      (is (= #{"orphan"} (offenders db "belongs to a Subsystem"))))))

(deftest membership-vacuous-without-subsystems
  (testing "no Subsystem modelled → the membership law is vacuous (guard)"
    (let [db (a/assemble-vars [#'t-orphan])]
      (is (empty? (offenders db "belongs to a Subsystem"))))))

(deftest fukan-every-module-is-clustered
  (testing "every fukan Module belongs to a subsystem"
    (is (empty? (offenders (pipeline/build-model nil) "belongs to a Subsystem")))))
