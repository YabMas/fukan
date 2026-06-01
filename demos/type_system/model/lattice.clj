(ns demos.type-system.model.lattice
  "A small type lattice modelled with the type-system vocabulary:

     Number                          (a primitive; not sealed)
     Int    <: Number
     Float  <: Number
     Point   = record { x: Int, y: Int }
     Point3D = record { x: Int, y: Int, z: Int }  <: Point   (width subtyping)

   Point3D keeps Point's field NAMES (x, y) and adds z, so width subtyping holds.
   NB: Point's and Point3D's `x`/`y` are DISTINCT Field nodes (handles px/py vs
   qx/qy) that merely share an `:fname` — the value-identity (#5) point, live."
  (:require [fukan.canvas.core.structure :as s]
            [demos.type-system.vocab.core :refer [Field Type]]))

(defn build []
  (s/with-structures
    (s/within-module "types"
      ;; primitive subtype chain
      (Type "Number")
      (Type "Int"   (subtype-of Number))
      (Type "Float" (subtype-of Number))

      ;; Point { x: Int, y: Int }
      (Field "px" (fname "x") (type Int))
      (Field "py" (fname "y") (type Int))
      (Type "Point" (field px) (field py))

      ;; Point3D <: Point — its own x/y Field nodes (distinct from Point's), plus z
      (Field "qx" (fname "x") (type Int))
      (Field "qy" (fname "y") (type Int))
      (Field "qz" (fname "z") (type Int))
      (Type "Point3D" (field qx) (field qy) (field qz) (subtype-of Point)))))
