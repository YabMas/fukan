(ns demos.type-system.model.lattice
  "A small type lattice modelled with the type-system vocabulary:

     NumberT                          (a primitive; not sealed)
     IntT    <: NumberT
     FloatT  <: NumberT
     Point   = record { x: IntT, y: IntT }
     Point3D = record { x: IntT, y: IntT, z: IntT }  <: Point   (width subtyping)

   Point3D keeps Point's field NAMES (x, y) and adds z, so width subtyping holds.
   NB: Point's and Point3D's `x`/`y` are DISTINCT Field nodes (handles px/py vs
   qx/qy) that merely share an `:fname` — the value-identity (#5) point, live.

   Name notes:
   - `Number` and `Float` clash with java.lang — instance vars renamed to NumberT / FloatT.
   - `Int` does not clash but is renamed to IntT for lattice consistency."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.type-system.vocab.core :refer [Field Type]]))

;; primitive types
(Type ^{:name "Number"} NumberT)
(Type ^{:name "Int"}   IntT   {:subtype-of [NumberT]})
(Type ^{:name "Float"} FloatT {:subtype-of [NumberT]})

;; Point { x: IntT, y: IntT }
(Field px {:fname "x" :type IntT})
(Field py {:fname "y" :type IntT})
(Type Point {:field [px py]})

;; Point3D <: Point — its own x/y Field nodes (distinct from Point's), plus z
(Field qx {:fname "x" :type IntT})
(Field qy {:fname "y" :type IntT})
(Field qz {:fname "z" :type IntT})
(Type Point3D {:field [qx qy qz] :subtype-of [Point]})

(defn build [] (a/assemble ['demos.type-system.model.lattice]))
