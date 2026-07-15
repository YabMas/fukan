(ns poly)

(defn area [s] (* 2 (:r s)))

(defmulti render-shape (fn [shape] (:kind shape)))

(defmethod render-shape :circle [s] (area s))
(defmethod render-shape :square [s] (:side s))

(defn describe [shape] (render-shape shape))
