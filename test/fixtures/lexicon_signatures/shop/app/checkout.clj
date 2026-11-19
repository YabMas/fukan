(ns shop.app.checkout
  "Code whose signatures are written in the domain's words: an attribute where one value
   passes, a view where a shape does. Read as forms and by clj-kondo; never loaded."
  (:require [lexicon.core :as lexicon]
            [shop.domain.lexicon :refer [vocabulary]]))

(def views
  (lexicon/views vocabulary
                 {:checkout/receipt   [:order/id :order/total :customer/name]
                  :checkout/placement [:order/id :order/customer]
                  :checkout/label     [:customer/id :customer/name]}))

(defn- pull
  "Stands in for a store read. Names no view; whoever calls it says which."
  [_view _id] {})

(defn- load-receipt
  "Names the view in its BODY — the one place this namespace asks the store for a shape."
  [id] (pull :checkout/receipt id))

(defn receipt
  {:malli/schema [:=> [:cat :order/id] :checkout/receipt]}
  [id] (load-receipt id))

(defn place
  {:malli/schema [:=> [:cat :customer/id [:vector :line/product]] :checkout/placement]}
  [customer _products] {:order/id 1 :order/customer customer})

(defn label
  {:malli/schema [:=> [:cat :customer/id] :checkout/label]}
  [id] {:customer/id id :customer/name ""})

(defn render
  {:malli/schema [:=> [:cat :checkout/receipt] :string]}
  [r] (str (:customer/name r) " " (:order/total r)))

(defn print-receipt
  {:malli/schema [:=> [:cat :order/id] :string]}
  [id] (render (receipt id)))
