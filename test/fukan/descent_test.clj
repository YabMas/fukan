(ns fukan.descent-test
  "The generative-descent Source slice: the structural-witness law read three ways."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [fukan.descent :as descent]))

(defn- fired?
  "True when the structural-witness law (its description read from the registered
   SourceRealizer structure) reports any violation over db."
  [db]
  (let [desc (-> (s/structure-by-tag :canvas.descent.source/SourceRealizer) :laws first :desc)]
    (boolean (some #(= desc (:law %)) (s/check db)))))

(deftest full-model-witnesses-both-polarities
  (testing "the real reflected model witnesses both polarities — verify/carve/gap all agree"
    (let [db (pipeline/build-model nil)]
      (is (= #{"design-down" "code-up"} (descent/required-witnesses db))
          "UP (carve): the Source portrait declares both flavours")
      (is (empty? (descent/unwitnessed-polarities db))
          "GAP (prompt): nothing unwitnessed — the in-fold is fully realized")
      (is (not (fired? db))
          "DOWN (verify): the structural-witness law passes on the real model"))))
