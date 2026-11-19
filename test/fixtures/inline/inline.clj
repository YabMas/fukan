(ns inline
  "Functions whose signatures are written INLINE, the way `malli.experimental/defn` takes them.
   Read by clj-kondo and as forms; never loaded."
  (:require [malli.experimental :as mx]
            [malli.util :as mu]
            [schema.core :as sc]))

(def Claim [:map [:id :int] [:held :boolean]])

(mx/defn one :- :int
  "One arity: the result after the name, a parameter after its binding."
  [x :- :string]
  (count x))

(mx/defn two
  "Two arities: each result after its own parameter vector; a bare parameter is `:any`."
  ([x :- :int] x)
  ([x :- :int, y] :- :string (str x y)))

(mx/defn rest* [x & more :- [:* :int]] (apply + x more))

(mx/defn ^:private destructured [{:keys [id]} :- Claim] id)

(mx/defn bare
  "Annotates nothing, so it writes no signature."
  [x] x)

(mx/defn stated
  {:malli/schema [:=> [:cat :boolean] :boolean]}
  [x :- :int] x)

(mx/defn computed :- (mu/dissoc Claim :held)
  "Its result is an EXPRESSION: a schema to malli, a list to a static read."
  [x :- :int]
  {:id x})

(sc/defn plumatic :- sc/Str
  "Another library's `defn` writes `:-` too, and its annotations are not malli."
  [x :- sc/Int]
  (str x))

(defn caller [] (destructured {:id (one "a")}))
