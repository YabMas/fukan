(ns fukan.vocabulary.allium.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.libs.allium.parser :as parser]
            [fukan.model.build :as build]))

(defn- ast [text]
  (parser/parse-allium (str "-- allium: 1\n" text)))

(deftest module-container-from-empty-file
  (testing "an empty .allium file produces one module-Container"
    (let [model (build/empty-model)
          a     (ast "")
          model (analyzer/analyze-file model a "test/module")]
      (is (some? (build/get-primitive model "test/module")))
      (is (= :primitive/container
             (:kind (build/get-primitive model "test/module"))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(= "Module" (-> % :tag :name)))
                         first)]
        (is (some? tag-app) "Allium::Module tag applied")
        (is (= "test/module" (-> tag-app :target :id)))))))

(deftest module-container-multiple-files
  (testing "analyze-file is composable across multiple files"
    (let [model (-> (build/empty-model)
                    (analyzer/analyze-file (ast "") "auth")
                    (analyzer/analyze-file (ast "") "billing"))]
      (is (some? (build/get-primitive model "auth")))
      (is (some? (build/get-primitive model "billing")))
      (is (= 2 (count (filter #(= "Module" (-> % :tag :name))
                              (:tag-apps model))))))))

(deftest module-container-label-defaults-to-coordinate
  (let [model (analyzer/analyze-file (build/empty-model) (ast "") "fukan/web/views")
        c (build/get-primitive model "fukan/web/views")]
    (is (= "fukan/web/views" (:label c)))))

(deftest entity-declaration
  (testing "entity becomes Container with Allium::Entity tag"
    (let [a (ast "entity Order { id: String, total: Integer }")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (let [c (build/get-primitive model "shop::Order")]
        (is (some? c))
        (is (= :primitive/container (:kind c)))
        (is (= 2 (count (:fields c))))
        (is (= "id" (-> c :fields first :name))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Entity" (-> % :tag :name))
                                       (= "shop::Order" (-> % :target :id))))
                         first)]
        (is (some? tag-app))))))

(deftest value-declaration
  (testing "value becomes Container with Allium::Value tag"
    (let [a (ast "value Money { amount: Integer, currency: String }")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (is (some? (build/get-primitive model "shop::Money")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "Value" (-> % :tag :name))
                                    (= "shop::Money" (-> % :target :id))))
                      first))))))

(deftest variant-declaration
  (testing "variant becomes Container with Allium::Variant tag and specialises edge"
    (let [a (ast "entity Node { id: String }\nvariant ModuleNode : Node { doc: String }")
          model (analyzer/analyze-file (build/empty-model) a "model")]
      (is (some? (build/get-primitive model "model::ModuleNode")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "Variant" (-> % :tag :name))
                                    (= "model::ModuleNode" (-> % :target :id))))
                      first)))
      (let [spec-edges (filter #(= :relation/specialises (:kind %))
                               (:edges model))]
        (is (= 1 (count spec-edges)))
        (is (= "model::ModuleNode" (-> spec-edges first :from :id)))
        (is (= "model::Node" (-> spec-edges first :to :id)))))))

(deftest variant-field-collision
  (testing "variant declaring a field name already on its parent throws"
    (is (thrown-with-msg?
          Exception #"(?i)field.*collision|collision"
          (let [a (ast "entity Node { name: String }\nvariant Mod : Node { name: Integer }")]
            (analyzer/analyze-file (build/empty-model) a "model"))))))

(deftest module-children-populated
  (testing "module-Container's :children includes all declared Containers"
    (let [a (ast "entity Order {}\nvalue Money {}\n")
          model (analyzer/analyze-file (build/empty-model) a "shop")
          module-c (build/get-primitive model "shop")]
      (is (contains? (:children module-c) "shop::Order"))
      (is (contains? (:children module-c) "shop::Money")))))

(deftest named-type-resolution-within-module
  (testing "a field's simple-name type-ref resolves to Composite(Named(...)) if declared in same module"
    (let [a (ast "value Money { amount: Integer }\nentity Order { total: Money }")
          model (analyzer/analyze-file (build/empty-model) a "shop")
          order (build/get-primitive model "shop::Order")
          total-field (->> (:fields order) (filter #(= "total" (:name %))) first)]
      (is (= :type/composite (-> total-field :type-ref :case)))
      (is (= :shape/named (-> total-field :type-ref :shape :case)))
      (is (= "shop::Money" (-> total-field :type-ref :shape :container))))))

(deftest field-with-builtin-scalar
  (testing "fields with built-in scalar type-refs (String, Integer, etc.) get Scalar Type"
    (let [a (ast "entity X { name: String, count: Integer }")
          model (analyzer/analyze-file (build/empty-model) a "test")
          x (build/get-primitive model "test::X")
          name-field (->> (:fields x) (filter #(= "name" (:name %))) first)]
      (is (= :type/scalar (-> name-field :type-ref :case)))
      (is (= "String" (-> name-field :type-ref :name))))))

(deftest external-entity-declaration
  (testing "external entity becomes Container with Allium::ExternalEntity tag"
    (let [a (ast "external entity Foo {}")
          model (analyzer/analyze-file (build/empty-model) a "user")]
      (is (some? (build/get-primitive model "user::Foo")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "ExternalEntity" (-> % :tag :name))
                                    (= "user::Foo" (-> % :target :id))))
                      first))))))

(deftest actor-declaration-simple
  (testing "actor with identified_by only"
    (let [a (ast "actor Author { identified_by: User }")
          model (analyzer/analyze-file (build/empty-model) a "interviews")]
      (is (some? (build/get-primitive model "interviews::Author")))
      (let [actor (build/get-primitive model "interviews::Author")]
        (is (= :primitive/actor (:kind actor))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Actor" (-> % :tag :name))
                                       (= "interviews::Author" (-> % :target :id))))
                         first)]
        (is (some? tag-app) "Allium::Actor tag applied")
        (is (= "User" (-> tag-app :payload :identified_by)))))))

(deftest actor-with-where-predicate
  (testing "actor with where predicate concatenates into identified_by text"
    (let [a (ast "actor Admin { identified_by: User where role = admin }")
          model (analyzer/analyze-file (build/empty-model) a "auth")
          tag-app (->> (:tag-apps model)
                       (filter #(= "Actor" (-> % :tag :name)))
                       first)]
      (is (= "User where role = admin" (-> tag-app :payload :identified_by))))))

(deftest actor-with-within
  (testing "actor with within clause populates within payload slot"
    (let [a (ast "actor Editor { identified_by: User within: Workspace }")
          model (analyzer/analyze-file (build/empty-model) a "docs")
          tag-app (->> (:tag-apps model)
                       (filter #(= "Actor" (-> % :tag :name)))
                       first)]
      (is (= "User" (-> tag-app :payload :identified_by)))
      (is (= "Workspace" (-> tag-app :payload :within))))))
