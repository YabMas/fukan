(ns fukan.cozo.check-test
  "P2 law oracle: a law ported onto Cozo must surface the SAME offenders as its
   datascript twin — on a synthetic violating fixture (non-empty) and on the real
   model (empty, since fukan's architecture is acyclic)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            ;; composition root — registers the extractor for build-model "src"
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.check :as check]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]
            [canvas.vocab.code.subsystem :as subsystem]))

(defn- ds-offenders
  "The datascript ModuleArchitecture law matched by `substr`, its offenders over
   `ds` as module/subsystem names."
  [ds substr]
  (let [desc (->> (s/structure-by-tag :canvas.vocab.code.subsystem/ModuleArchitecture)
                  :laws (map :desc) (filter #(str/includes? % substr)) first)]
    (->> (s/check ds) (filter #(= desc (:law %))) (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity ds %))) set)))

(defn- cozo-via
  "Mirror `ds` into Cozo, run the cozo offender-query `f`, close the db."
  [ds f]
  (let [cdb (mirror/mirror ds)]
    (try (f cdb) (finally (db/close cdb)))))

;; ── no-mutual-dependency: MA's op delegates to MB's op and MB's back to MA's ──
(declare t-mb-op)
(operation/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(operation/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(module/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(module/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest mutual-oracle-matches-on-a-violation
  (testing "cozo == datascript no-mutual-dependency offenders on a synthetic cycle"
    (let [ds (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (ds-offenders ds "mutually depend")) "precondition: datascript flags it")
      (is (= (ds-offenders ds "mutually depend") (cozo-via ds check/mutually-dependent-modules))))))

;; ── acyclicity: a :may-depend 2-cycle CY-A ⇄ CY-B ──
(declare t-sub-cy-b)
(subsystem/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(subsystem/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest acyclicity-oracle-matches-on-a-cycle
  (testing "cozo == datascript acyclicity offenders on a synthetic :may-depend cycle"
    (let [ds (a/assemble-vars [#'t-sub-cy-a #'t-sub-cy-b])]
      (is (= #{"CY-A" "CY-B"} (ds-offenders ds "acyclic")) "precondition: datascript flags it")
      (is (= (ds-offenders ds "acyclic") (cozo-via ds check/cyclic-subsystems))))))

;; ── conformance: M-S → M-T cross-subsystem, S-bad does NOT declare :may-depend T ──
(operation/Operation ^{:name "op-t"} t-op-t "callee in T")
(operation/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(module/Module ^{:name "M-S"} t-cm-s {:exposes [t-op-s]})
(module/Module ^{:name "M-T"} t-cm-t {:exposes [t-op-t]})
(declare t-sub-T)
(subsystem/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})  ; declares no :may-depend
(subsystem/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-oracle-matches-on-a-violation
  (testing "cozo == datascript conformance offenders on an undeclared cross-subsystem dep"
    (let [ds (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])]
      (is (= #{"M-S"} (ds-offenders ds "cross-subsystem")) "precondition: datascript flags it")
      (is (= (ds-offenders ds "cross-subsystem") (cozo-via ds check/nonconformant-modules))))))

(deftest oracle-matches-on-the-real-model
  (testing "every ported law agrees (and is empty) on the real, conformant model"
    (let [ds (pipeline/build-model "src")]
      (is (= (ds-offenders ds "mutually depend") (cozo-via ds check/mutually-dependent-modules)))
      (is (= (ds-offenders ds "acyclic") (cozo-via ds check/cyclic-subsystems)))
      (is (= (ds-offenders ds "cross-subsystem") (cozo-via ds check/nonconformant-modules)))
      (is (empty? (ds-offenders ds "acyclic")) "precondition: fukan is acyclic"))))
