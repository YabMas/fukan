(ns shop.domain.orders
  "Declares views through an alias, one per var, and two inside one function."
  (:require [shop.domain.lexicon :as lexicon]))

(def Summary
  (lexicon/view [:order/id :order/total :customer/name]))

(def WithLines
  (lexicon/view [:order/id
                 {'(:order/lines {:where [:> :line/quantity 1]}) [:line/product]}]))

(defn pair
  []
  [(lexicon/view [:customer/id]) (lexicon/view [:customer/name])])

(def not-a-view
  "Mentions vocabulary keywords without declaring a view; selects nothing."
  {:order/total 0 :customer/name "nobody"})
