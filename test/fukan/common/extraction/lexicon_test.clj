(ns fukan.common.extraction.lexicon-test
  "The Lexicon producer over a fixture project (`test/fixtures/lexicon`): a vocabulary of three
   entities and a value shape beside a schema it does not list, and views named through an alias
   and a referred name — one made beforehand, one from a selection that is not a literal, and
   one with no name at all."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(def ^:private root "test/fixtures/lexicon")

(defn- roots [] (:roots (lx/extract-roots [root])))

(defn- model
  "The fixture's Clojure facts and Lexicon facts on one graph, as the composition root builds it."
  []
  (build/model->cozo [] {:roots (into (:roots (tc/extract-roots [root])) (roots))}))

(defn- named [tag]
  (set (for [[_ iv] (roots) :when (= tag (:tag iv))] (:name iv))))

(defn- clause [id rk]
  (let [iv (some (fn [[i iv]] (when (= id i) iv)) (roots))]
    (mapv :id (:targets (some #(when (= rk (:rk %)) %) (:clauses iv))))))

(deftest a-project-without-the-library-has-no-lexicon-facts
  (is (= {:roots []} (lx/extract-roots ["test/fixtures/inline"]))))

(deftest a-shape-is-what-the-vocabulary-lists
  (testing "the three entities and the value shape are read; the map no vocabulary lists is not"
    (is (= #{"customer" "order" "line"} (named ::lx/Entity)))
    (is (= #{"address"} (named ::lx/Value))))
  (testing "a shape carries its declaration's docstring"
    (is (= "One purchase." (:doc (some (fn [[i iv]] (when (= ":order" i) iv)) (roots)))))))

(deftest an-attribute-says-what-it-is
  (let [kind (into {} (for [[_ iv] (roots) :when (= ::lx/Attribute (:tag iv))]
                        [(:name iv) (:val/kind (:scalars iv))]))]
    (is (= "stored" (kind "order/total")))
    (is (= "ref" (kind "order/customer")))
    (is (= "many" (kind "order/lines")))
    (is (= "derived" (kind "order/large?")))
    (is (= "stored" (kind "address/city")) "a value shape's keys are attributes as an entity's are"))
  (testing "a ref names its entity; a many names the entity whose reference it inverts"
    (is (= [":customer"] (clause ":order/customer" :refers-to)))
    (is (= [":line"] (clause ":order/lines" :refers-to))))
  (testing "a reference to an entity the vocabulary does not declare keeps its kind, loses its target"
    (is (= [] (clause ":line/product" :refers-to))))
  (testing "an attribute embeds the value shape its schema nests, bare or inside a collection"
    (is (= [":address"] (clause ":customer/address" :embeds)))
    (is (= [":address"] (clause ":customer/previous" :embeds)))))

(deftest containment-is-held-by-the-container
  (is (= [":order"] (clause ":customer" :part)))
  (is (= [":line"] (clause ":order" :part)))
  (is (= [] (clause ":line" :part))))

(deftest a-view-is-its-name
  (testing "through an alias, with the attributes of another entity it reaches"
    (is (= [":customer/name" ":order/id" ":order/total"] (clause "view:orders/summary" :selects)))
    (is (= ["shop.domain.orders"] (clause "view:orders/summary" :declared-in))))
  (testing "a nested selection and the condition inside it are both what the use depends on"
    (is (= [":line/product" ":line/quantity" ":order/id" ":order/lines"]
           (clause "view:orders/with-lines" :selects))))
  (testing "a view made beforehand, over a value shape"
    (is (= [":address/city"] (clause "view:orders/home" :selects))))
  (testing "through a referred name"
    (is (= [":order/large?" ":order/placed-at"] (clause "view:page/header" :selects))))
  (testing "two uses may each have a summary, and neither is the other"
    (is (= [":customer/id" ":customer/name"] (clause "view:page/summary" :selects))))
  (testing "a view of a selection that is not a literal is emitted, selecting nothing"
    (is (= [] (clause "view:page/opaque" :selects))))
  (testing "a view with no name, and vocabulary keywords outside any view, make no view"
    (is (= #{"orders/summary" "orders/with-lines" "orders/home"
             "page/header" "page/summary" "page/opaque"}
           (named ::lx/View)))))

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
    (testing "which namespace depends on knowing which shape"
      (is (= #{["shop.domain.orders" "order"] ["shop.domain.orders" "customer"]
               ["shop.domain.orders" "line"] ["shop.domain.orders" "address"]
               ["shop.web.page" "order"] ["shop.web.page" "customer"]}
             (lx/context-map db))))
    (testing "where one attribute is asked for"
      (is (= #{["shop.domain.orders" "orders/summary"] ["shop.web.page" "page/summary"]}
             (lx/attribute-readers db "customer/name"))))))
