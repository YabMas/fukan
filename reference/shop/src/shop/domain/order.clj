(ns shop.domain.order
  "What the shop knows: customers, the products it sells, orders and their lines. Plain Malli
   maps; the `:lexicon/*` properties say what Malli has no word for.")

(def Address
  "Where something is sent. No identity of its own: it is part of whatever holds it."
  [:map {:lexicon/value true}
   [:address/street :string]
   [:address/city :string]])

(def Customer
  "Somebody who buys."
  [:map
   [:customer/id :int]
   [:customer/name :string]
   [:customer/address Address]
   [:customer/orders {:lexicon/many :order/customer :optional true} [:* :int]]])

(def Product
  "Something for sale."
  [:map
   [:product/id :int]
   [:product/title :string]
   [:product/price :int]])

(def Order
  "One purchase."
  [:map
   [:order/id :int]
   [:order/customer {:lexicon/ref :customer :lexicon/parent true} :int]
   [:order/total :int]
   [:order/lines {:lexicon/many :line/order :optional true} [:* :int]]])

(def Line
  "One product on one order."
  [:map
   [:line/id :int]
   [:line/order {:lexicon/ref :order :lexicon/parent true} :int]
   [:line/product {:lexicon/ref :product} :int]
   [:line/quantity [:int {:min 1}]]])
