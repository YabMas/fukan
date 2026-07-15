(ns fukan.vocab.relation-reflection-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.model.pipeline :as p]
            ;; composition root (so the build reflects the full vocab)
            [fukan.infra.model]))

(defn- relation [db nm]
  (some->> (cq/q '[:find ?e :in $ ?n
                   :where [?e :structure/of :fukan.canvas.core.reflect/Relation] [?e :entity/name ?n]] db nm)
           ffirst (cq/entity db)))

(deftest relation-kinds-are-reflected-with-their-characters
  (testing "grammar reflection reifies relation KINDS as Relation nodes carrying their declared characters"
    (let [db (p/build-model nil)]
      ;; the grammar's edge vocabulary is now reified, like its node vocabulary
      (is (some? (relation db "child"))     ":child is reified as a Relation node")
      (is (some? (relation db "delegates")) ":delegates is reified as a Relation node")
      ;; characters ride the relation node (the property OF the relation)
      (is (true? (:val/contains (relation db "child")))      ":child carries the :contains character")
      (is (true? (:val/transitive (relation db "delegates"))) ":delegates carries the :transitive character")
      (is (nil? (:val/contains (relation db "delegates")))    ":delegates is not :contains")
      (is (nil? (:val/transitive (relation db "exposes")))    ":exposes carries no character"))))
