(ns lib.arch-test
  "The opt-in clean-architecture quality layer: no two modules mutually depend."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [lib.code :as code]
            [lib.arch]))

(defn- law-desc []
  (-> (s/structure-by-tag :lib.arch/ModuleArchitecture) :laws first :desc))

(defn- offenders [db]
  (->> (s/check db) (filter #(= (law-desc) (:law %)))
       (mapcat :offenders) (map first) (map #(:entity/name (d/entity db %))) set))

;; a synthetic mutual pair: A's op delegates to B's op and B's op delegates to A's op
(declare t-mb-op)
(code/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(code/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(code/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(code/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest mutual-dependency-fires
  (testing "two modules whose ops mutually delegate violate the no-mutual-dependency law"
    (let [db (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (offenders db))))))

(deftest fukan-module-graph-has-no-mutual-dependency
  (testing "fukan's own module graph is acyclic — the quality law is a green opt-in"
    (is (empty? (offenders (pipeline/build-model nil))))))
