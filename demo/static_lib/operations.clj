(ns demo.static-lib.operations
  "Canvas port — cross-cutting geometric operations spanning vec2, vec3, matrix, transform.
   Lower-bound stress-test: construction primitives only."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function exports]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "static-lib.operations"

      (function "apply_transform_to_vec3"
        "Apply a 3x3 matrix to a 3D vector. Returns the transformed vector."
        (takes [m :static-lib.matrix/Matrix3x3]
               [v :static-lib.vec3/Vec3])
        (gives :static-lib.vec3/Vec3))

      (function "project_vec3_to_vec2"
        "Project a 3D vector onto 2D by dropping the z component."
        (takes [v :static-lib.vec3/Vec3])
        (gives :static-lib.vec2/Vec2))

      (function "embed_vec2_in_vec3"
        "Embed a 2D vector into 3D by appending a z component."
        (takes [v :static-lib.vec2/Vec2] [z :Double])
        (gives :static-lib.vec3/Vec3))

      (function "lerp_vec2"
        "Linear interpolate between two 2D vectors. t=0 returns a, t=1 returns b."
        (takes [a :static-lib.vec2/Vec2]
               [b :static-lib.vec2/Vec2]
               [t :Double])
        (gives :static-lib.vec2/Vec2))

      (function "lerp_vec3"
        "Linear interpolate between two 3D vectors."
        (takes [a :static-lib.vec3/Vec3]
               [b :static-lib.vec3/Vec3]
               [t :Double])
        (gives :static-lib.vec3/Vec3))

      (function "apply_transform_to_vec2"
        "Apply an affine transform to a 2D vector (via homogeneous embedding)."
        (takes [t :static-lib.transform/Transform]
               [v :static-lib.vec2/Vec2])
        (gives :static-lib.vec2/Vec2)))))
