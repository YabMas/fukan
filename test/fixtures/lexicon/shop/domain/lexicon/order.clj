(ns shop.domain.lexicon.order
  "The order entities of the fixture vocabulary. Read as forms by the Lexicon producer's tests;
   never loaded.")

(def Address
  "A value shape: no identity, contained by being nested."
  [:map {:lexicon/value true}
   [:address/street :string]
   [:address/city :string]])

(def Customer
  "Somebody who buys."
  [:map
   [:customer/id :int]
   [:customer/name :string]
   [:customer/address Address]
   [:customer/previous {:optional true} [:vector Address]]
   [:customer/orders {:lexicon/many :order/customer :optional true} [:* :int]]])

(def Order
  "One purchase."
  [:map
   [:order/id :int]
   [:order/customer {:lexicon/ref :customer :lexicon/parent true} :int]
   [:order/placed-at inst?]
   [:order/total :int]
   [:order/large? {:lexicon/derived [:order/total]} :boolean]
   [:order/lines {:lexicon/many :line/order :optional true} [:* :int]]])

(def Line
  [:map
   [:line/id :int]
   [:line/order {:lexicon/ref :order :lexicon/parent true} :int]
   [:line/product {:lexicon/ref :product} :int]
   [:line/quantity :int]])

(def Mixed
  "An ordinary schema living beside the shapes: no vocabulary lists it."
  [:map
   [:mixed/id :int]
   [:order/total :int]])
