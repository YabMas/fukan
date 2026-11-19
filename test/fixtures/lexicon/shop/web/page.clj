(ns shop.web.page
  "Names views through a referred name: one literal, one whose selection is not, and a second
   `summary` that is not the orders' one."
  (:require [lexicon.core :refer [views]]
            [shop.domain.lexicon :refer [vocabulary]]))

(def selection [:order/placed-at])

(def page
  (views vocabulary
         {:page/header  [:order/placed-at :order/large?]
          :page/summary [:customer/id :customer/name]
          :page/opaque  selection}))
