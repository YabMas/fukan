(ns demos.type-system.type-system-test
  "Regression for the type-system demo: the lattice is well-formed, and each kind
   of ill-formedness — a subtype cycle, a width-subtyping violation, subtyping a
   sealed type, a non-boolean :sealed? flag — is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.type-system.model.lattice :as lattice]
            [demos.type-system.vocab.core :refer [Field Type]]
            [lib.type.malli]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest lattice-is-well-formed
  (testing "the primitive chain + Point/Point3D width subtyping satisfies every law"
    (is (empty? (s/check (lattice/build))))))

;; ── case 1: subtype cycle — A <: B, B <: A ───────────────────────────────────
;; Both c1-A and c1-B are used as targets before their own defs, so both need
;; forward declarations.  The var-capture mechanism means the captured vars are
;; resolved at assemble time (when all defs are bound), not at construction time.

(declare c1-A c1-B)
(Type ^{:name "A"} c1-A {:subtype-of [c1-B]})   ; forward reference to c1-B
(Type ^{:name "B"} c1-B {:subtype-of [c1-A]})   ; forward reference to c1-A

(deftest subtype-cycle-is-caught
  (testing "a type that transitively subtypes itself trips the acyclic-lattice law"
    (let [db (a/assemble-vars [#'c1-A #'c1-B])]
      (is (contains? (laws db) "no cycle in the subtype lattice")))))

;; ── case 2: width-subtyping violation ────────────────────────────────────────

(Type ^{:name "Prim"} c2-Prim)
(Field ^{:name "sid"} c2-sid {:fname "id" :type c2-Prim})
(Type ^{:name "Super"} c2-Super {:field [c2-sid]})
(Type ^{:name "Sub"} c2-Sub {:subtype-of [c2-Super]})   ; lacks a field named "id"

(deftest width-subtyping-violation-is-caught
  (testing "a subtype that omits a field name its supertype declares is caught"
    (let [db (a/assemble-vars [#'c2-Prim #'c2-sid #'c2-Super #'c2-Sub])]
      (is (contains? (laws db) "a subtype must declare every field name of its supertypes")))))

;; ── case 3: subtyping a sealed type ──────────────────────────────────────────

(Type ^{:name "Final"} c3-Final {:sealed? true})
(Type ^{:name "Bad"} c3-Bad {:subtype-of [c3-Final]})

(deftest subtype-of-sealed-is-caught
  (testing "subtyping a sealed type is caught (a leaf-Bool value driving a law)"
    (let [db (a/assemble-vars [#'c3-Final #'c3-Bad])]
      (is (contains? (laws db) "nothing may be a subtype of a sealed type")))))

;; ── case 4: non-boolean :sealed? flag ────────────────────────────────────────

(Type ^{:name "T"} c4-T {:sealed? "yes"})   ; not a Bool

(deftest sealed-flag-must-be-boolean
  (testing "a non-boolean :sealed? value is caught by the value-type law"
    (let [db (a/assemble-vars [#'c4-T])]
      (is (contains? (laws db) "Type.sealed? value must satisfy :boolean")))))
