(ns fukan.canvas.vocab.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.validation :refer [checker]]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest checker-creates-affordance-with-fixed-shape
  (testing "(checker …) produces an Affordance with the baked-in (Model) -> [Violation] arrow"
    (let [db (h/with-canvas
               (h/within-module "validation.rules_4a"
                 (checker "check" "Run phase-4a structural checks.")))
          rows (d/q '[:find ?n ?r ?sh
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]
                             [?a :node/shape ?sh]]
                    db)
          [n role sh-eid] (first rows)
          shape (store/read-reified-shape db sh-eid)]
      (is (= 1 (count rows)))
      (is (= "check" n))
      (is (= :canvas/checker role))
      (is (= :arrow (:kind shape)))
      (is (= [["model" {:kind :ref :target :model/Model}]]
             (-> shape :inputs :fields)))
      (is (= {:kind :list :elem {:kind :ref :target :agent/Violation}}
             (:outputs shape))))))

(deftest checker-emits-cross-module-refs
  (testing "(checker …) creates :references Relations for Model and Violation"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (checker "rules_4a" "Phase 4a sub-rules.")
                 (checker "rules_4b" "Phase 4b sub-rules.")))
          refs (d/q '[:find ?to
                      :where [_ :references ?to]]
                    db)
          targets (set (map first refs))]
      (is (contains? targets :model/Model))
      (is (contains? targets :agent/Violation)))))

(deftest multiple-checkers-coexist
  (testing "declaring several checkers in one module produces several Affordances"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (checker "rules_4a" "Phase 4a.")
                 (checker "rules_4b" "Phase 4b.")
                 (checker "rules_4c" "Phase 4c.")))
          rows (d/q '[:find ?n
                      :where [?a :affordance/role :canvas/checker]
                             [?a :entity/name ?n]]
                    db)]
      (is (= #{"rules_4a" "rules_4b" "rules_4c"} (set (map first rows)))))))

(deftest checker-persists-doc
  (testing "(checker …) stores the docstring in :affordance/doc"
    (let [db (h/with-canvas
               (h/within-module "validation.rules_4a"
                 (checker "check" "Run phase-4a structural checks.")))
          rows (d/q '[:find ?n ?doc
                      :where [?a :affordance/role :canvas/checker]
                             [?a :entity/name ?n]
                             [?a :affordance/doc ?doc]]
                    db)]
      (is (= [["check" "Run phase-4a structural checks."]] (vec rows))))))
