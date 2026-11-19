(ns shop.domain.lexicon
  "The fixture's vocabulary, declared the one way there is: the library's `vocabulary` over a
   literal vector of shapes. Read as forms by the Lexicon producer's tests; never loaded."
  (:require [lexicon.core :as lexicon]
            [shop.domain.lexicon.order :as order]))

(def vocabulary
  (lexicon/vocabulary [order/Customer order/Order order/Line order/Address]))
