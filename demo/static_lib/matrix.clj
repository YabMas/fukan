(ns demo.static-lib.matrix
  "Canvas port — 3x3 matrix type and pure operations.
   Lower-bound stress-test: construction primitives only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "static-lib.matrix"

      (record "Matrix3x3"
        "A 3x3 matrix stored as a flat row-major sequence of 9 doubles."
        (field m00 :Double)
        (field m01 :Double)
        (field m02 :Double)
        (field m10 :Double)
        (field m11 :Double)
        (field m12 :Double)
        (field m20 :Double)
        (field m21 :Double)
        (field m22 :Double))

      (function "identity_matrix"
        "Return the 3x3 identity matrix."
        (takes [])
        (gives :Matrix3x3))

      (function "add"
        "Add two matrices element-wise."
        (takes [a :Matrix3x3] [b :Matrix3x3])
        (gives :Matrix3x3))

      (function "mul"
        "Multiply two 3x3 matrices."
        (takes [a :Matrix3x3] [b :Matrix3x3])
        (gives :Matrix3x3))

      (function "transpose"
        "Return the transpose of the matrix."
        (takes [m :Matrix3x3])
        (gives :Matrix3x3))

      (function "determinant"
        "Compute the determinant of the matrix."
        (takes [m :Matrix3x3])
        (gives :Double))

      (function "scale_uniform"
        "Construct a uniform scale matrix."
        (takes [s :Double])
        (gives :Matrix3x3))

      (exports Matrix3x3))))
