(ns shop.front
  "Where a request becomes something the shop understands. The only namespace that speaks
   both languages: it takes the request-handling vocabulary's words and gives the shop's."
  (:require [lexicon.core :as lexicon]
            [lexicon.install :as install]
            [malli.experimental :as mx]
            [shop.checkout :as checkout]
            [shop.domain :as domain]
            [shop.store :as store]))

(install/views!
 (lexicon/views domain/vocabulary
                {:front/visitor [:login/id :login/customer :login/session]}))

(mx/defn customer :- [:maybe :customer/id]
  "Who is asking, or nil for a visitor not signed in."
  [request :- :web/caller]
  (when-let [session (:web.request/session request)]
    (:login/customer (store/find-one :front/visitor :login/session session))))

(mx/defn receipt :- [:maybe :checkout/receipt]
  "The receipt a request asks for, when the one asking is signed in."
  [request :- :web/caller, order :- :order/id]
  (when (customer request)
    (checkout/receipt order)))
