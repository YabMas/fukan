(ns shop.checkout
  "Taking an order and showing what was bought."
  (:require [clojure.string :as str]
            [lexicon.core :as lexicon]
            [lexicon.install :as install]
            [malli.experimental :as mx]
            [shop.domain :as domain]
            [shop.store :as store]))

(install/views!
 (lexicon/views domain/vocabulary
                {:checkout/receipt   [:order/id :order/total :customer/name]
                 :checkout/placement [:order/id :order/customer :order/total]
                 :checkout/summary   [:order/id :order/total]
                 :checkout/price     [:product/id :product/price]}))

(mx/defn place :- :checkout/placement
  "A customer buys some products, one of each."
  [id :- :order/id, customer :- :customer/id, products :- [:vector :line/product]]
  (let [prices (map #(:product/price (store/pull :checkout/price %)) products)
        order  {:order/id id :order/customer customer :order/total (reduce + 0 prices)}]
    (store/put! :order order)
    order))

(mx/defn receipt :- [:maybe :checkout/receipt]
  "An order as the customer sees it, or nil for no such order."
  [id :- :order/id]
  (store/pull :checkout/receipt id))

(mx/defn summary :- [:maybe :checkout/summary]
  "An order in one line's worth."
  [id :- :order/id]
  (store/pull :checkout/summary id))

(mx/defn render :- :string
  "A receipt, as text."
  [r :- :checkout/receipt]
  (str/join " " [(:customer/name r) "order" (:order/id r) "total" (:order/total r)]))
