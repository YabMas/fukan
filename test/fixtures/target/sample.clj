(ns sample)

(defn ^{:malli/schema [:=> [:cat :int] :int]} alpha [x] x)

(defn beta [x y] (+ x y))

(defn- delta [x] (inc x))

(def gamma 42)
