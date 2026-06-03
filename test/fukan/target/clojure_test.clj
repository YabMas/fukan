(ns fukan.target.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.target.clojure :as tc :refer [Capability]]))

(deftest extracts-defns-as-code-structures
  (testing "the trivial extractor emits a Defn per defn, with arity"
    (let [db (tc/extract "test/fixtures/target/sample.clj")
          defns (into {} (d/q '[:find ?n ?a
                                :where [?e :structure/of :Defn] [?e :entity/name ?n] [?e :val/arity ?a]]
                              db))]
      (is (= {"alpha" 1 "beta" 2} defns)
          "both defns extracted with correct arity; the def (gamma) is ignored"))))

(deftest cross-layer-property-verifiable-on-the-merged-graph
  (testing "a Capability is realized by an extracted Defn — verified only because both share the graph"
    (let [extracted (tc/extract "test/fixtures/target/sample.clj")
          authored  (s/with-structures
                      (s/within-module "caps"
                        (Capability "A" (realizes "alpha"))        ; matches an extracted defn
                        (Capability "B" (realizes "beta"))         ; matches
                        (Capability "Gone" (realizes "missing")))) ; no such defn
          merged    (cs/merge-dbs [extracted authored])
          offenders (->> (s/check merged)
                         (filter #(= "a capability is realized by an extracted defn" (:law %)))
                         (mapcat :offenders) (map first) set)
          name-of   (fn [eid] (:entity/name (d/entity merged eid)))]
      (is (= #{"Gone"} (set (map name-of offenders)))
          "only the capability naming a non-extracted defn violates — A and B are satisfied"))))
