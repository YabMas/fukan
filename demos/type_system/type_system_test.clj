(ns demos.type-system.type-system-test
  "Regression for the type-system demo: the lattice is well-formed, and each kind
   of ill-formedness — a subtype cycle, a width-subtyping violation, subtyping a
   sealed type, a non-boolean :sealed? flag — is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [demos.type-system.model.lattice :as lattice]
            [demos.type-system.vocab.core :refer [Field Type]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest lattice-is-well-formed
  (testing "the primitive chain + Point/Point3D width subtyping satisfies every law"
    (is (empty? (s/check (lattice/build))))))

(deftest subtype-cycle-is-caught
  (testing "a type that transitively subtypes itself trips the acyclic-lattice law"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Type "A" (subtype-of B))     ; forward ref; cycle authored via two-pass
                 (Type "B" (subtype-of A))))]
      (is (contains? (laws db) "no cycle in the subtype lattice")))))

(deftest width-subtyping-violation-is-caught
  (testing "a subtype that omits a field name its supertype declares is caught"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Type "Prim")
                 (Field "sid" (fname "id") (type Prim))
                 (Type "Super" (field sid))
                 (Type "Sub" (subtype-of Super))))]   ; lacks a field named "id"
      (is (contains? (laws db) "a subtype must declare every field name of its supertypes")))))

(deftest subtype-of-sealed-is-caught
  (testing "subtyping a sealed type is caught (a leaf-Bool value driving a law)"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Type "Final" (sealed? true))
                 (Type "Bad" (subtype-of Final))))]
      (is (contains? (laws db) "nothing may be a subtype of a sealed type")))))

(deftest sealed-flag-must-be-boolean
  (testing "a non-boolean :sealed? value is caught by the value-type law"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Type "T" (sealed? "yes"))))]        ; not a Bool
      (is (contains? (laws db) "Type.sealed? value must be a Bool")))))
