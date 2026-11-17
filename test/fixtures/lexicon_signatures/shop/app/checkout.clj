(ns shop.app.checkout
  "Code whose signatures are written in the domain's language: an attribute where one value
   passes, a view where a shape does. Read as forms and by clj-kondo; never loaded."
  (:require [shop.domain.lexicon :as lexicon]))

(def Receipt   (lexicon/view [:order/id :order/total :customer/name]))
(def Placement (lexicon/view [:order/id :order/customer]))
(def Label     (lexicon/view [:customer/id :customer/name]))

(defn receipt
  {:malli/schema [:=> [:cat :order/id] Receipt]}
  [id] {:order/id id :order/total 0 :customer/name ""})

(defn place
  {:malli/schema [:=> [:cat :customer/id [:vector :line/product]] Placement]}
  [customer _products] {:order/id 1 :order/customer customer})

(defn label
  {:malli/schema [:=> [:cat :customer/id] Label]}
  [id] {:customer/id id :customer/name ""})

(defn render
  {:malli/schema [:=> [:cat Receipt] :string]}
  [r] (str (:customer/name r) " " (:order/total r)))

(defn print-receipt
  {:malli/schema [:=> [:cat :order/id] :string]}
  [id] (render (receipt id)))
