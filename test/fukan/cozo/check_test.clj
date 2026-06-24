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

;; ── membership: an authored Module in no Subsystem (with a Subsystem present) ──
(module/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(module/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(subsystem/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-oracle-matches-on-a-violation
  (testing "cozo == datascript membership offenders on an unclustered authored module"
    (let [ds (a/assemble-vars [#'t-orphan #'t-homed #'t-sub-home])]
      (is (= #{"orphan"} (ds-offenders ds "belongs to a Subsystem")) "precondition: datascript flags it")
      (is (= (ds-offenders ds "belongs to a Subsystem") (cozo-via ds check/unclustered-modules))))))

(deftest membership-oracle-matches-when-vacuous
  (testing "no Subsystem modelled → both empty (the vacuity guard)"
    (let [ds (a/assemble-vars [#'t-orphan])]
      (is (empty? (ds-offenders ds "belongs to a Subsystem")) "precondition: datascript vacuous")
      (is (= (ds-offenders ds "belongs to a Subsystem") (cozo-via ds check/unclustered-modules))))))

;; ── Encapsulation: a public extracted op with no authored twin ──
(operation/Operation ^{:name "loose"} t-loose {:extracted true})
(module/Module ^{:name "fukan.x.thing"} t-thing {:child [t-loose]})

(deftest encapsulation-oracle-matches-on-a-violation
  (testing "cozo == datascript Encapsulation offenders on an uncovered public extracted op"
    (let [ds (a/assemble-vars [#'t-loose #'t-thing])]
      (is (= #{"loose"} (operation/uncovered-public-operations ds)) "precondition: datascript flags it")
      (is (= (operation/uncovered-public-operations ds) (cozo-via ds check/uncovered-public-operations))))))

;; ── Realization: an authored op with no extracted twin (+ an extracted op to satisfy the guard) ──
(operation/Operation ^{:name "extracted-thing"} t-ext {:extracted true})
(operation/Operation ^{:name "ghost"} t-ghost "authored, unrealized")
(module/Module ^{:name "fukan.realm"} t-realm {:child [t-ghost t-ext]})

(deftest realization-oracle-matches-on-a-violation
  (testing "cozo == datascript Realization offenders on an authored op with no twin"
    (let [ds (a/assemble-vars [#'t-ext #'t-ghost #'t-realm])]
      (is (= #{"ghost"} (operation/drifted-operations ds)) "precondition: datascript flags it")
      (is (= (operation/drifted-operations ds) (cozo-via ds check/drifted-operations))))))

;; ── CallRealization: an authored cross-module delegation with no realizing call ──
(declare t-db)
(operation/Operation ^{:name "da"} t-da {:delegates [t-db]})
(operation/Operation ^{:name "db"} t-db "authored callee in b")
(module/Module ^{:name "a"} t-mod-a {:exposes [t-da]})
(module/Module ^{:name "b"} t-mod-b {:exposes [t-db]})
;; fukan.guard satisfies the ≥1-extracted-Module guard AND holds an (unrelated) extracted
;; call so datascript's not-join isn't over an empty :calls relation — its empty-relation
;; gotcha would otherwise mis-fire on this synthetic (cozo's stratified negation does not).
(operation/Operation ^{:name "ge2"} t-ge2 {:extracted true})
(operation/Operation ^{:name "ge"}  t-ge  {:extracted true :calls [t-ge2]})
(module/Module ^{:name "fukan.guard"} t-guard {:extracted true :child [t-ge t-ge2]})

(deftest call-realization-oracle-matches-on-a-violation
  (testing "cozo == datascript CallRealization offenders on an unrealized delegation"
    (let [ds (a/assemble-vars [#'t-da #'t-db #'t-mod-a #'t-mod-b #'t-ge #'t-ge2 #'t-guard])]
      (is (= #{"da"} (module/unrealized-delegates ds)) "precondition: datascript flags it")
      (is (= (module/unrealized-delegates ds) (cozo-via ds check/unrealized-delegates))))))

(deftest oracle-matches-on-the-real-model
  (testing "every ported law agrees (and is empty) on the real, conformant model"
    (let [ds (pipeline/build-model "src")]
      (is (= (ds-offenders ds "mutually depend") (cozo-via ds check/mutually-dependent-modules)))
      (is (= (ds-offenders ds "acyclic") (cozo-via ds check/cyclic-subsystems)))
      (is (= (ds-offenders ds "cross-subsystem") (cozo-via ds check/nonconformant-modules)))
      (is (= (ds-offenders ds "belongs to a Subsystem") (cozo-via ds check/unclustered-modules)))
      (is (= (operation/uncovered-public-operations ds) (cozo-via ds check/uncovered-public-operations)))
      (is (= (operation/drifted-operations ds) (cozo-via ds check/drifted-operations)))
      (is (= (module/unrealized-delegates ds) (cozo-via ds check/unrealized-delegates)))
      (is (empty? (ds-offenders ds "acyclic")) "precondition: fukan is acyclic"))))
