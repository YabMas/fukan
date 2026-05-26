(ns demo.static-lib.vec2
  "Canvas port — 2D vector type and pure operations.
   Lower-bound stress-test: construction primitives only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "static-lib.vec2"

      (record "Vec2"
        "A 2D vector with x and y components."
        (field x :Double)
        (field y :Double))

      (function "add"
        "Add two vectors component-wise. Returns a + b."
        (takes [a :Vec2] [b :Vec2])
        (gives :Vec2))

      (function "sub"
        "Subtract b from a component-wise. Returns a - b."
        (takes [a :Vec2] [b :Vec2])
        (gives :Vec2))

      (function "dot"
        "Compute the dot product of two vectors. Returns a scalar."
        (takes [a :Vec2] [b :Vec2])
        (gives :Double))

      (function "magnitude"
        "Compute the Euclidean length of a vector."
        (takes [v :Vec2])
        (gives :Double))

      (function "normalize"
        "Return the unit vector in the direction of v. Returns nil for zero vector."
        (takes [v :Vec2])
        (gives (optional :Vec2)))

      (exports Vec2))))
