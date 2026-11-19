(ns shop.domain.orders
  "Names its views through an alias: plain selections, one with a condition, and one made
   beforehand over a value shape."
  (:require [lexicon.core :as lexicon]
            [shop.domain.lexicon :refer [vocabulary]]))

(def views
  (lexicon/views vocabulary
                 {:orders/summary    [:order/id :order/total :customer/name]
                  :orders/with-lines [:order/id
                                      {'(:order/lines {:where [:> :line/quantity 1]}) [:line/product]}]
                  :orders/home       (lexicon/view vocabulary [:address/city] :address)}))

(def loose
  "A view with no name: nothing outside this namespace could name it, and it is not in the model."
  (lexicon/view vocabulary [:customer/id]))

(def not-a-view
  "Mentions vocabulary keywords without declaring a view; selects nothing."
  {:order/total 0 :customer/name "nobody"})

(defn home
  "Names a view in its body."
  [pull id]
  (pull :orders/home id))
