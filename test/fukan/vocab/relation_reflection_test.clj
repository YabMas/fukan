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

(deftest relation-kinds-are-reflected-with-their-inclusions
  (testing "grammar reflection reifies relation KINDS as Relation nodes carrying their declared
            inclusions — the same (direction, expression) shape a RelationMap uses"
    (let [db (p/build-model nil)]
      ;; the grammar's edge vocabulary is now reified, like its node vocabulary
      (is (some? (relation db "child"))     ":child is reified as a Relation node")
      (is (some? (relation db "delegates")) ":delegates is reified as a Relation node")
      (is (some? (relation db "contains"))  "the GENUS is reified too — a BARE element, no slot of its own")
      ;; the inclusion rides the relation node (the property OF the relation)
      (is (= ":sub" (:val/incl (relation db "child")))       ":child ⊑ contains — the inclusion direction")
      (is (= ":contains" (:val/expr (relation db "child")))  "…and its expression, the genus atom")
      (is (= ":contains" (:val/expr (relation db "exposes"))) ":exposes is a species too — declared once, on the relation")
      (is (= ":eq" (:val/incl (relation db "within")))    "a derived element is definitionally exact — :eq to its rule")
      (is (nil? (:val/incl (relation db "contains")))        "a bare genus states no inclusion of its own")
      (is (nil? (:val/incl (relation db "delegates")))       ":delegates is slot-only — not an element yet, no inclusion")
      (is (nil? (:val/transitive (relation db "contains")))  "closures are the compiler's — no :transitive property at all"))))
