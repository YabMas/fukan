(ns canvas.constraint.well-known-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.constraint.well-known :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "constraint.well-known") "module name present")
    (is (contains? names "SignalGapSemantics") "SignalGapSemantics invariant present")
    (is (contains? names "ExternalMustHaveWrapperSemantics") "ExternalMustHaveWrapperSemantics invariant present")
    (is (contains? names "NoDependencySemantics") "NoDependencySemantics invariant present")
    (is (contains? names "NoCircularRefsSemantics") "NoCircularRefsSemantics invariant present")
    (is (contains? names "NamingConventionSemantics") "NamingConventionSemantics invariant present")
    (is (contains? names "FactoryPurity") "FactoryPurity invariant present")
    (is (contains? names "IdempotentRegistration") "IdempotentRegistration invariant present")
    (is (contains? names "signal_gap") "signal_gap function present")
    (is (contains? names "external_must_have_wrapper") "external_must_have_wrapper function present")
    (is (contains? names "no_dependency") "no_dependency function present")
    (is (contains? names "no_circular_refs") "no_circular_refs function present")
    (is (contains? names "naming_convention") "naming_convention function present")))
