(ns fukan.common.extraction.lexicon-test
  "The Lexicon producer over a fixture project (`test/fixtures/lexicon`): a vocabulary of three
   entities beside two schemas that are not entities, and views declared through an alias, a
   referred name, twice in one function, and once from a selection that is not a literal."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(def ^:private root "test/fixtures/lexicon")

(def ^:private sources
  '{:vocabulary ["shop.domain.lexicon"] :view #{shop.domain.lexicon/view}})

(defn- roots []
  (binding [lx/*sources* sources] (:roots (lx/extract-roots [root]))))

(defn- model
  "The fixture's Clojure facts and Lexicon facts on one graph, as the composition root builds it."
  []
  (build/model->cozo [] {:roots (into (:roots (tc/extract-roots [root])) (roots))}))

(defn- named [tag]
  (set (for [[_ iv] (roots) :when (= tag (:tag iv))] (:name iv))))

(defn- clause [id rk]
  (let [iv (some (fn [[i iv]] (when (= id i) iv)) (roots))]
    (mapv :id (:targets (some #(when (= rk (:rk %)) %) (:clauses iv))))))

(deftest unconfigured-it-adds-nothing
  (testing "a project that names no sources has no Lexicon facts, and no file is read to find out"
    (is (= {:roots []} (lx/extract-roots [root])))))

(deftest an-entity-is-a-one-namespace-map-with-an-id
  (testing "the three entities are read; the id-less map and the two-namespace map are not"
    (is (= #{"customer" "order" "line"} (named ::lx/Entity))))
  (testing "an entity carries its declaration's docstring"
    (is (= "One purchase." (:doc (some (fn [[i iv]] (when (= ":order" i) iv)) (roots)))))))

(deftest an-attribute-says-what-it-is
  (let [kind (into {} (for [[_ iv] (roots) :when (= ::lx/Attribute (:tag iv))]
                        [(:name iv) (:val/kind (:scalars iv))]))]
    (is (= "stored" (kind "order/total")))
    (is (= "ref" (kind "order/customer")))
    (is (= "many" (kind "order/lines")))
    (is (= "derived" (kind "order/large?"))))
  (testing "a ref names its entity; a many names the entity whose reference it inverts"
    (is (= [":customer"] (clause ":order/customer" :refers-to)))
    (is (= [":line"] (clause ":order/lines" :refers-to))))
  (testing "a reference to an entity the vocabulary does not declare keeps its kind, loses its target"
    (is (= [] (clause ":line/product" :refers-to)))))

(deftest containment-is-held-by-the-container
  (is (= [":order"] (clause ":customer" :part)))
  (is (= [":line"] (clause ":order" :part)))
  (is (= [] (clause ":line" :part))))

(deftest a-view-is-read-where-it-is-declared
  (testing "through an alias, with the attributes of another entity it reaches"
    (is (= [":customer/name" ":order/id" ":order/total"]
           (clause "view:shop.domain.orders/Summary" :selects)))
    (is (= ["shop.domain.orders"] (clause "view:shop.domain.orders/Summary" :declared-in))))
  (testing "a nested selection and the condition inside it are both what the use depends on"
    (is (= [":line/product" ":line/quantity" ":order/id" ":order/lines"]
           (clause "view:shop.domain.orders/WithLines" :selects))))
  (testing "through a referred name"
    (is (= [":order/large?" ":order/placed-at"] (clause "view:shop.web.page/Header" :selects))))
  (testing "two views in one function are two views"
    (is (= [":customer/id"] (clause "view:shop.domain.orders/pair" :selects)))
    (is (= [":customer/name"] (clause "view:shop.domain.orders/pair@14" :selects))))
  (testing "a view of a selection that is not a literal is emitted, selecting nothing"
    (is (= [] (clause "view:shop.web.page/Opaque" :selects))))
  (testing "vocabulary keywords outside any view make no view"
    (is (= #{"Summary" "WithLines" "pair" "pair@14" "Header" "Opaque"} (named ::lx/View)))))

(deftest the-facts-assemble-beside-the-code-and-break-no-law
  (let [db (model)]
    (testing "every root resolved, and the generated slot laws hold"
      (is (= [] (law/check db))))
    (testing "the facts are extracted, not declared design"
      (is (= 3 (count (cq/q '[:find ?e :where [?e :structure/of ::lx/Entity] [?e :val/extracted true]]
                            db)))))
    (testing "the domain as declared"
      (is (= {:entities   #{"customer" "order" "line"}
              :parts      #{["customer" "order"] ["order" "line"]}
              :references #{["order" "customer"] ["customer" "order"] ["line" "order"] ["order" "line"]}}
             (lx/domain-map db))))
    (testing "which namespace depends on knowing which entity"
      (is (= #{["shop.domain.orders" "order"] ["shop.domain.orders" "customer"]
               ["shop.domain.orders" "line"] ["shop.web.page" "order"]}
             (lx/context-map db))))
    (testing "where one attribute is asked for"
      (is (= #{["shop.domain.orders" "Summary"] ["shop.domain.orders" "pair@14"]}
             (lx/attribute-readers db "customer/name"))))))
