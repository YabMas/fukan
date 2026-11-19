(ns design.front
  (:require [design.checkout :as checkout]
            [design.store :as store]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module front "Where a request becomes something the shop understands."
  (Operation customer "Who is asking, or nobody for a visitor not signed in."
    {:signature [:=> [:cat :web/caller] [:maybe :customer/id]]
     :delegates [store/find-one]})
  (Operation receipt "The receipt a request asks for, when the one asking is signed in."
    {:signature [:=> [:cat :web/caller :order/id] [:maybe :checkout/receipt]]
     :delegates [customer checkout/receipt]}))
