(ns demo.static-lib.transform
  "Canvas port — affine transform type and operations over Matrix3x3.
   Lower-bound stress-test: construction primitives only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "static-lib.transform"

      (record "Transform"
        "An affine transform represented as a 3x3 homogeneous matrix.
         The matrix encodes both rotation and translation in 2D space."
        (field matrix :static-lib.matrix/Matrix3x3))

      (function "identity_transform"
        "Return the identity transform (no rotation, no translation)."
        (takes [])
        (gives :Transform))

      (function "translation"
        "Construct a translation transform by (dx, dy)."
        (takes [dx :Double] [dy :Double])
        (gives :Transform))

      (function "rotation"
        "Construct a rotation transform by angle (radians)."
        (takes [angle :Double])
        (gives :Transform))

      (function "compose"
        "Compose two transforms: apply b first, then a. Returns a new transform."
        (takes [a :Transform] [b :Transform])
        (gives :Transform))

      (function "invert"
        "Compute the inverse of a transform. Returns nil if not invertible."
        (takes [t :Transform])
        (gives (optional :Transform)))

      (exports Transform))))
