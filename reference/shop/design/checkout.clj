(ns design.checkout
  (:require [design.store :as store]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module checkout "Taking an order and showing what was bought."
  (Operation place "A customer buys some products, one of each."
    {:signature [:=> [:cat :order/id :customer/id [:vector :line/product]] :checkout/placement]
     :delegates [store/pull store/put!]})
  (Operation receipt "An order as the customer sees it."
    {:signature [:=> [:cat :order/id] [:maybe :checkout/receipt]]
     :delegates [store/pull]})
  (Operation summary "An order in one line's worth."
    {:signature [:=> [:cat :order/id] [:maybe :checkout/summary]]
     :delegates [store/pull]})
  (Operation render "A receipt, as text."
    {:signature [:=> [:cat :checkout/receipt] :string]}))
