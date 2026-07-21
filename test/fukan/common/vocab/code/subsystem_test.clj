(ns fukan.common.vocab.code.subsystem-test
  "The opt-in clean-architecture quality layer: the module-dependency graph is acyclic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            [fukan.canvas.core.structure :as s]
            ;; the composition root — registers fukan's Clojure FACT extractor (so `build-model "src"`
            ;; merges extracted code onto the design graph) AND loads the Cozo check engine for s/check
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.common.vocab.code.operation :as operation]
            [fukan.common.vocab.code.module :as module]
            [fukan.common.vocab.code.subsystem :as subsystem]))

(defn- law-desc
  "The matching law desc — every module/subsystem law now rides `Subsystem`: its own :may-depend
   slot-semantics laws plus the rehomed module-graph acyclicity + membership-totality demands."
  [substr]
  (->> (:laws (s/structure-by-tag :fukan.common.vocab.code.subsystem/Subsystem))
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (law/check db) (filter #(= desc (:law %)))
         (mapcat :offenders) (map first) (map #(:entity/name (cq/entity db %))) set)))

;; a synthetic mutual pair: A's op delegates to B's op and B's op delegates to A's op
(declare t-mb-op)
(operation/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(operation/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(module/Module ^{:name "MA"} t-mod-ma {:child [t-ma-op]})
(module/Module ^{:name "MB"} t-mod-mb {:child [t-mb-op]})

(deftest module-acyclicity-fires-on-a-mutual-pair
  (testing "two modules whose ops mutually delegate (a 2-cycle) violate the acyclicity law"
    (let [db (build/vars->cozo [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (offenders db "module transitively"))))))

;; a synthetic 3-cycle A→B→C→A (each op delegates to the next module's op): NO direct mutual pair,
;; so the old 2-cycle check saw nothing — the transitive SCC law catches all three.
(declare t3-b-op t3-c-op)
(operation/Operation ^{:name "t3a-op"} t3-a-op {:delegates [t3-b-op]})
(operation/Operation ^{:name "t3b-op"} t3-b-op {:delegates [t3-c-op]})
(operation/Operation ^{:name "t3c-op"} t3-c-op {:delegates [t3-a-op]})
(module/Module ^{:name "T3A"} t3-mod-a {:child [t3-a-op]})
(module/Module ^{:name "T3B"} t3-mod-b {:child [t3-b-op]})
(module/Module ^{:name "T3C"} t3-mod-c {:child [t3-c-op]})

(deftest module-acyclicity-fires-on-a-transitive-cycle
  (testing "a 3-module cycle T3A→T3B→T3C→T3A — no direct mutual pair, so the OLD 2-cycle check
            missed it; the SCC law flags all three (each transitively depends on itself)"
    (let [db (build/vars->cozo [#'t3-a-op #'t3-b-op #'t3-c-op #'t3-mod-a #'t3-mod-b #'t3-mod-c])]
      (is (= #{"T3A" "T3B" "T3C"} (offenders db "module transitively"))))))

(deftest fukan-module-graph-is-acyclic
  (testing "fukan's own module graph is acyclic — no transitive cycle, the quality law is a green opt-in"
    (is (empty? (offenders (pipeline/build-model nil) "module transitively")))))

;; ── conformance fixtures: S's op delegates to T's op (cross-subsystem) ──
(operation/Operation ^{:name "op-t"} t-op-t "callee in T")
(operation/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(module/Module ^{:name "M-S"} t-cm-s {:child [t-op-s]})
(module/Module ^{:name "M-T"} t-cm-t {:child [t-op-t]})
(declare t-sub-T)
(subsystem/Subsystem ^{:name "S-ok"}  t-sub-S-ok  {:child [t-cm-s] :may-depend [t-sub-T]})  ; declares the dep
(subsystem/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})                          ; does NOT
(subsystem/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-green-when-cross-dep-is-declared
  (testing "M-S → M-T conforms because subsystem S-ok declares :may-depend T"
    (let [db (build/vars->cozo [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-ok #'t-sub-T])]
      (is (empty? (offenders db "cross-subsystem"))))))

(deftest conformance-fires-on-undeclared-cross-dep
  (testing "M-S → M-T violates because S-bad does NOT declare :may-depend T"
    (let [db (build/vars->cozo [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])]
      (is (= #{"M-S"} (offenders db "cross-subsystem"))))))

;; ── over-declaration fixtures: V declares :may-depend T but M-V realizes no dependency on M-T ──
(operation/Operation ^{:name "op-v"} t-op-v "a V op that depends on nothing cross-subsystem")
(module/Module ^{:name "M-V"} t-cm-v {:child [t-op-v]})
(subsystem/Subsystem ^{:name "V"} t-sub-V {:child [t-cm-v] :may-depend [t-sub-T]})

(deftest unrealized-dependency-fires-on-an-over-declared-edge
  (testing "V declares :may-depend T but M-V depends on no module in T → [V T] is an unrealized dependency"
    (let [db (build/vars->cozo [#'t-op-v #'t-cm-v #'t-sub-V #'t-op-t #'t-cm-t #'t-sub-T])]
      (is (= #{["V" "T"]} (subsystem/unrealized-dependencies db))))))

(deftest unrealized-dependency-green-when-the-declared-dep-is-realized
  (testing "S-ok declares :may-depend T AND M-S depends on M-T → nothing is reported unrealized"
    (let [db (build/vars->cozo [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-ok #'t-sub-T])]
      (is (empty? (subsystem/unrealized-dependencies db))))))

(deftest fukan-has-no-unrealized-declared-dependencies
  (testing "after tightening ingestion to :may-depend [], every declared :may-depend edge is realized by code"
    (is (empty? (subsystem/unrealized-dependencies (pipeline/build-model "src"))))))

;; ── acyclicity fixtures: a 2-cycle in :may-depend ──
(declare t-sub-cy-b)
(subsystem/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(subsystem/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest may-depend-acyclicity-fires-on-a-cycle
  (testing "a :may-depend cycle CY-A ⇄ CY-B violates the acyclicity law"
    (let [db (build/vars->cozo [#'t-sub-cy-a #'t-sub-cy-b])]
      (is (= #{"CY-A" "CY-B"} (offenders db "subsystem transitively"))))))

(deftest fukan-may-depend-graph-is-acyclic
  (testing "fukan's declared :may-depend DAG is acyclic"
    (is (empty? (offenders (pipeline/build-model nil) "subsystem transitively")))))

;; ── membership fixtures: a module in no subsystem (with a subsystem present) ──
(module/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(module/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(module/Module ^{:name "ext-orphan"} t-ext-orphan "a code-fact module, in no subsystem (stamped fact at build)")
(subsystem/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-ignores-extracted-modules
  (testing "an extracted (code-fact) module in no subsystem is NOT a membership offender"
    ;; t-ext-orphan is stamped fact-stratum at BUILD time (fact-vars->cozo) — provenance is the
    ;; pipeline's, not an authoring slot
    (let [db (build/fact-vars->cozo [#'t-homed #'t-sub-home] [#'t-ext-orphan])]
      (is (empty? (offenders db "belongs to a Subsystem"))
          "design-membership is for authored modules; extracted modules are out of scope"))))

(deftest membership-fires-on-unclustered-module
  (testing "with a Subsystem present, a Module in none is an offender"
    (let [db (build/vars->cozo [#'t-orphan #'t-homed #'t-sub-home])]
      (is (= #{"orphan"} (offenders db "belongs to a Subsystem"))))))

(deftest membership-vacuous-without-subsystems
  (testing "no Subsystem modelled → the membership law is vacuous (guard)"
    (let [db (build/vars->cozo [#'t-orphan])]
      (is (empty? (offenders db "belongs to a Subsystem"))))))

(deftest fukan-every-module-is-clustered
  (testing "every fukan Module belongs to a subsystem"
    (is (empty? (offenders (pipeline/build-model nil) "belongs to a Subsystem")))))

;; (The latent-boundaries interface-segregation discovery reading was retired with the
;; canvas/principles/ layer; only its two module-graph enforcement laws were rehomed onto Subsystem.)
