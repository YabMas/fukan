(ns design.billing
  (:require [design.checkout :as checkout]
            [design.store :as store]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module billing "What a customer owes and where the bill goes."
  (Operation summary "Who is billed, and where."
    {:signature [:=> [:cat :customer/id] [:maybe :billing/summary]]
     :delegates [store/pull]})
  (Operation destination "Where a customer's bill is sent."
    {:signature [:=> [:cat :customer/id] [:maybe :customer/address]]
     :delegates [summary]})
  (Operation owed "What an order comes to."
    {:signature [:=> [:cat :order/id] :order/total]
     :delegates [checkout/summary]}))
