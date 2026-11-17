(ns shop.domain.lexicon
  "The fixture's vocabulary and its view maker, closed over it as an adopter's is."
  (:require [shop.domain.lexicon.order :as order]))

(def entities [order/Customer order/Order order/Line])

(defn view
  "Stands in for the library's view, closed over the vocabulary."
  [selection]
  {:vocabulary entities :selection selection})
