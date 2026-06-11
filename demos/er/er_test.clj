(ns demos.er.er-test
  "Regression for the ER demo: the shop schema is well-formed, and each kind of
   ill-formedness is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.er.model.shop :as shop]
            [demos.er.vocab.core :refer [DataType Attribute Relationship Entity]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest shop-schema-is-well-formed
  (testing "the shop schema builds and satisfies every law"
    (is (empty? (s/check (shop/build))))))

;; ── case 1: entity with no attribute ─────────────────────────────────────────

(def c1-String (DataType "String"))
(def c1-n      (Attribute "n" (type c1-String)))
(def c1-Empty  (Entity "Empty"))                       ; no :attr → trips [:+ Attribute]

(deftest entity-needs-an-attribute
  (testing "an entity with no attribute trips the [:+ Attribute] cardinality"
    (let [db (a/assemble-vars [#'c1-String #'c1-n #'c1-Empty])]
      (is (contains? (laws db) "Entity.attr requires at least one (found none)")))))

;; ── case 2: relationship targeting a non-Entity ───────────────────────────────

(def c2-String (DataType "String"))
(def c2-n      (Attribute "n" (type c2-String)))
(def c2-bad    (Relationship "bad" (target c2-String)))  ; targets a DataType, not an Entity
(def c2-E      (Entity "E" (attr c2-n) (rel c2-bad)))

(deftest relationship-must-target-an-entity
  (testing "a relationship whose target is a DataType, not an Entity, is caught"
    (let [db (a/assemble-vars [#'c2-String #'c2-n #'c2-bad #'c2-E])]
      ;; the slot is named `target`, so the target-type law desc reads
      ;; "Relationship.target target must be a Entity"
      (is (contains? (laws db) "Relationship.target target must be a Entity")))))

;; ── case 3: non-boolean flag ──────────────────────────────────────────────────

(def c3-String (DataType "String"))
(def c3-bad    (Attribute "bad" (type c3-String) (required "yes")))   ; not a Bool

(deftest attribute-flag-must-be-boolean
  (testing "a non-boolean :required flag is caught by the value-type law"
    (let [db (a/assemble-vars [#'c3-String #'c3-bad])]
      (is (contains? (laws db) "Attribute.required value must be a Bool")))))

;; ── case 4: circular dependency — A→B→A ──────────────────────────────────────
;; Both c4-A and c4-B are used as targets before their own defs, so both need
;; forward declarations.  The var-capture mechanism means the captured vars are
;; resolved at assemble time (when all defs are bound), not at construction time.

(declare c4-A c4-B)
(def c4-String (DataType "String"))
(def c4-n      (Attribute "n" (type c4-String)))
(def c4-to-b   (Relationship "to-b" (target c4-B)))   ; forward reference to c4-B
(def c4-to-a   (Relationship "to-a" (target c4-A)))   ; forward reference to c4-A
(def c4-A      (Entity "A" (attr c4-n) (rel c4-to-b)))
(def c4-B      (Entity "B" (attr c4-n) (rel c4-to-a)))

(deftest circular-dependency-is-caught
  (testing "a reference cycle A→B→A — authorable via forward declare — is caught"
    (let [db (a/assemble-vars [#'c4-String #'c4-n #'c4-to-b #'c4-to-a #'c4-A #'c4-B])]
      (is (contains? (laws db) "no circular dependency among entities")))))
