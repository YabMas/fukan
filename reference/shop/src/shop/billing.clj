(ns shop.billing
  "What a customer owes and where the bill goes. It has a `summary` of its own, which is not
   checkout's: a view's name is whole."
  (:require [lexicon.core :as lexicon]
            [malli.experimental :as mx]
            [shop.checkout :as checkout]
            [shop.domain :as domain]
            [shop.store :as store]))

(domain/install!
 (lexicon/views domain/vocabulary
                {:billing/summary [:customer/id :customer/name :customer/address]}))

(mx/defn summary :- [:maybe :billing/summary]
  "Who is billed, and where."
  [id :- :customer/id]
  (store/pull :billing/summary id))

(mx/defn destination :- [:maybe :customer/address]
  "Where a customer's bill is sent. A value shape passes under the name of the attribute
   holding it."
  [id :- :customer/id]
  (:customer/address (summary id)))

(mx/defn owed :- :order/total
  "What an order comes to."
  [id :- :order/id]
  (:order/total (checkout/summary id) 0))
