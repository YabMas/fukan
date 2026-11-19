(ns surface
  "Functions whose signatures use malli beyond the handful of types a first annotation reaches
   for — as `:malli/schema` metadata and inline. Read by clj-kondo and as forms; never loaded."
  (:require [malli.experimental :as mx]
            [malli.util :as mu]))

(def Claim [:map [:id :int]])

(defn ^{:malli/schema [:=> [:cat :qualified-keyword :uuid] [:maybe :some]]}
  types [k id] [k id])

(defn ^{:malli/schema [:=> [:cat pos-int? [string? {:min 1}]] boolean?]}
  predicates [n s] (and n s))

(defn ^{:malli/schema [:-> [:enum :open :closed 1 nil] [:re #"^a+$"] [:fn (fn [x] (pos? x))] :nil]}
  values [_state _text _n] nil)

(defn ^{:malli/schema [:function
                       [:=> [:cat [:orn [:id :int] [:name :string]]] [:map {:closed true} [:id :int] [:order/total {:optional true} [:> 0]]]]
                       [:=> [:cat :int [:multi {:dispatch :type} [:a [:map [:type [:= :a]]]] [:malli.core/default :any]]] [:tuple :int [:set :keyword]]]]}
  entries
  ([x] x)
  ([x y] [x y]))

(defn ^{:malli/schema [:=> [:cat [:schema {:registry {::tree [:orn [:leaf :int] [:node [:vector [:ref ::tree]]]]}} ::tree]] :int]}
  recursive [_t] 0)

(defn ^{:malli/schema [:=> [:cat :int :int] :int [:fn (fn [[[a b] r]] (= r (+ a b)))]]}
  guarded [a b] (+ a b))

(mx/defn inline :- [:map-of :keyword [:sequential [:or :int :double]]]
  [x :- [:and :int [:>= 0]], & more :- [:* [:alt :keyword :string]]]
  {:x [x (count more)]})

(defn ^{:malli/schema [:=> [:cat (mu/select-keys Claim [:id])] :any]}
  computed [c] c)
