(ns design.store
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module store "Where the shop's facts are kept. It reads what a view asks for and nothing else."
  (Operation put! "Keep an entity's attributes under the id they carry."
    {:signature [:=> [:cat :keyword [:map-of :keyword :any]] :nil]})
  (Operation pull "An entity as a named view selects it, or nothing for no such entity."
    {:signature [:=> [:cat :keyword :int] [:maybe [:map-of :keyword :any]]]})
  (Operation find-one "The one entity whose attribute holds a value, as a named view selects it."
    {:signature [:=> [:cat :keyword :keyword :any] [:maybe [:map-of :keyword :any]]]
     :delegates [pull]})
  (Operation clear! "Forget everything."
    {:signature [:=> [:cat] :nil]}))
