(ns demos.er.er-test
  "Regression for the ER demo: the shop schema is well-formed, and each kind of
   ill-formedness is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [demos.er.model.shop :as shop]
            [demos.er.vocab.core :refer [DataType Attribute Relationship Entity]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest shop-schema-is-well-formed
  (testing "the shop schema builds and satisfies every law"
    (is (empty? (s/check (shop/build))))))

(deftest entity-needs-an-attribute
  (testing "an entity with no attribute trips the (some Attribute) cardinality"
    (let [db (s/with-structures
               (s/within-module "shop"
                 (DataType "String")
                 (Attribute "n" (type String))
                 (Entity "Empty")))]            ; no :attr
      (is (contains? (laws db) "Entity.attr requires at least one (found none)")))))

(deftest relationship-must-target-an-entity
  (testing "a relationship whose target is a DataType, not an Entity, is caught"
    (let [db (s/with-structures
               (s/within-module "shop"
                 (DataType "String")
                 (Attribute "n" (type String))
                 (Relationship "bad" (target String))   ; targets a DataType
                 (Entity "E" (attr n) (rel bad))))]
      ;; the slot is named `target`, so the target-type law desc reads
      ;; "Relationship.target target must be a Entity"
      (is (contains? (laws db) "Relationship.target target must be a Entity")))))

(deftest circular-dependency-is-caught
  (testing "a reference cycle A→B→A — now AUTHORABLE via forward references — is caught"
    (let [db (s/with-structures
               (s/within-module "shop"
                 (DataType "String")
                 (Attribute "n" (type String))
                 (Relationship "to-b" (target B))   ; forward reference to B
                 (Relationship "to-a" (target A))
                 (Entity "A" (attr n) (rel to-b))
                 (Entity "B" (attr n) (rel to-a))))]
      (is (contains? (laws db) "no circular dependency among entities")))))
