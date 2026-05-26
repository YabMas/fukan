(ns demo.static-lib.vec3
  "Canvas port — 3D vector type and pure operations.
   Lower-bound stress-test: construction primitives only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "static-lib.vec3"

      (record "Vec3"
        "A 3D vector with x, y, and z components."
        (field x :Double)
        (field y :Double)
        (field z :Double))

      (function "add"
        "Add two 3D vectors component-wise. Returns a + b."
        (takes [a :Vec3] [b :Vec3])
        (gives :Vec3))

      (function "sub"
        "Subtract b from a component-wise. Returns a - b."
        (takes [a :Vec3] [b :Vec3])
        (gives :Vec3))

      (function "dot"
        "Compute the dot product of two 3D vectors."
        (takes [a :Vec3] [b :Vec3])
        (gives :Double))

      (function "cross"
        "Compute the cross product of two 3D vectors. Returns a vector perpendicular to both."
        (takes [a :Vec3] [b :Vec3])
        (gives :Vec3))

      (function "magnitude"
        "Compute the Euclidean length of a 3D vector."
        (takes [v :Vec3])
        (gives :Double))

      (function "normalize"
        "Return the unit vector in the direction of v. Returns nil for zero vector."
        (takes [v :Vec3])
        (gives (optional :Vec3)))

      (exports Vec3))))
