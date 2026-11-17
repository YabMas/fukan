(ns shop.domain.lexicon.order
  "The order entities of the fixture vocabulary. Read as forms by the Lexicon producer's tests;
   never loaded.")

(def Customer
  "Somebody who buys."
  [:map
   [:customer/id :int]
   [:customer/name :string]
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

(def Address
  "Not an entity: no id key. An ordinary schema living beside the entities."
  [:map
   [:address/street :string]
   [:address/city :string]])

(def Mixed
  "Not an entity: its keys are from two namespaces."
  [:map
   [:mixed/id :int]
   [:order/total :int]])
