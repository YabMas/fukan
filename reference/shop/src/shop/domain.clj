(ns shop.domain
  "The shop's vocabulary.

   Every shape that flows through the system is named here or by a view over what is named
   here. A signature is written in those names and nothing else: an attribute where one value
   passes (`:order/id`), a view where a shape does (`:checkout/receipt`)."
  (:require [lexicon.core :as lexicon]
            [lexicon.install :as install]
            [shop.domain.order :as order]))

(def vocabulary
  (install/vocabulary!
   (lexicon/vocabulary [order/Address order/Customer order/Product order/Order order/Line])))
