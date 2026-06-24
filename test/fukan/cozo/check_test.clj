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
            ;; registers the ModuleArchitecture law so datascript's `check` runs it
            ;; (the synthetic db is assembled standalone, not via build-model)
            [canvas.vocab.code.subsystem]))

(defn- ds-mutual-offenders
  "The datascript no-mutual-dependency law's offenders (module names) over `ds`."
  [ds]
  (let [desc (->> (s/structure-by-tag :canvas.vocab.code.subsystem/ModuleArchitecture)
                  :laws (map :desc) (filter #(str/includes? % "mutually depend")) first)]
    (->> (s/check ds) (filter #(= desc (:law %))) (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity ds %))) set)))

(defn- cozo-mutual-offenders [ds]
  (let [cdb (mirror/mirror ds)]
    (try (check/mutually-dependent-modules cdb) (finally (db/close cdb)))))

;; a synthetic mutual pair: MA's op delegates to MB's op and MB's op back to MA's
(declare t-mb-op)
(operation/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(operation/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(module/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(module/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest oracle-matches-on-a-violation
  (testing "cozo law offenders == datascript law offenders on a synthetic cycle (non-empty)"
    (let [ds (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (ds-mutual-offenders ds)) "precondition: datascript flags the cycle")
      (is (= (ds-mutual-offenders ds) (cozo-mutual-offenders ds))))))

(deftest oracle-matches-on-the-real-model
  (testing "both empty on the real, acyclic model"
    (let [ds (pipeline/build-model "src")]
      (is (empty? (ds-mutual-offenders ds)) "precondition: fukan is acyclic")
      (is (= (ds-mutual-offenders ds) (cozo-mutual-offenders ds))))))
