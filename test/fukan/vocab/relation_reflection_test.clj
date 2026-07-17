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
      (is (some? (relation db "contains"))  "the GENUS is reified too — a relation element with no slot of its own")
      ;; characters ride the relation node (the property OF the relation)
      (is (= "contains" (:val/isa (relation db "child")))     ":child is a species of the contains genus")
      (is (= "contains" (:val/isa (relation db "exposes")))   ":exposes is a species too — declared once, on the relation")
      (is (true? (:val/transitive (relation db "contains")))  "the genus is transitive — contains+ rolls the ladder up")
      (is (true? (:val/transitive (relation db "delegates"))) ":delegates carries the :transitive character")
      (is (nil? (:val/isa (relation db "delegates")))         ":delegates is no species of contains")
      (is (nil? (:val/transitive (relation db "exposes")))    ":exposes is a species, but not transitive"))))
