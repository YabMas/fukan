(ns shop.web.page
  "Declares a view through a referred name, and one whose selection is not a literal."
  (:require [shop.domain.lexicon :refer [view]]))

(def selection [:order/placed-at])

(def Header (view [:order/placed-at :order/large?]))

(def Opaque (view selection))
