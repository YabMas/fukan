(ns fukan.canvas.vocab.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.lifecycle :refer [getter]]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest getter-creates-affordance-with-optional-shape
  (testing "(getter \"name\" :T) produces an Affordance returning Optional<T>"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (getter "get_port" "Current port if running." :Integer)))
          rows (d/q '[:find ?n ?r ?sh
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]
                             [?a :node/shape ?sh]]
                    db)
          [n role sh-eid] (first rows)
          shape (store/read-reified-shape db sh-eid)]
      (is (= 1 (count rows)))
      (is (= "get_port" n))
      (is (= :canvas/getter role))
      (is (= :arrow (:kind shape)))
      (is (= {:kind :record :fields []} (:inputs shape)))
      (is (= {:kind :optional :inner {:kind :atomic :name :Integer}}
             (:outputs shape))))))

(deftest getter-with-cross-module-type-emits-ref
  (testing "(getter \"name\" :module/Type) emits a :references Relation"
    (let [db (h/with-canvas
               (h/within-module "infra.model"
                 (getter "get_model" "Current model if loaded." :model/Model)))
          refs (d/q '[:find ?to
                      :where [_ :references ?to]]
                    db)]
      (is (contains? (set (map first refs)) :model/Model)))))

(deftest multiple-getters-coexist
  (testing "multiple getters in one module produce separate Affordances"
    (let [db (h/with-canvas
               (h/within-module "infra.model"
                 (getter "get_model" "Model."   :model/Model)
                 (getter "refresh_model" "Refreshed model." :model/Model)
                 (getter "get_src" "Source path." :FilePath)))
          rows (d/q '[:find ?n
                      :where [?a :affordance/role :canvas/getter]
                             [?a :entity/name ?n]]
                    db)]
      (is (= #{"get_model" "refresh_model" "get_src"} (set (map first rows)))))))

(deftest getter-persists-doc
  (testing "(getter …) stores the docstring in :affordance/doc"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (getter "get_port" "Current port if running." :Integer)))
          rows (d/q '[:find ?n ?doc
                      :where [?a :affordance/role :canvas/getter]
                             [?a :entity/name ?n]
                             [?a :affordance/doc ?doc]]
                    db)]
      (is (= [["get_port" "Current port if running."]] (vec rows))))))
