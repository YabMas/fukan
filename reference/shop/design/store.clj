(ns design.store
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module store "Where the shop's facts are kept. It reads what a view asks for and nothing else."
  (Operation put! "Keep an entity's attributes under the id they carry."
    {:signature [:=> [:cat :keyword [:map-of :keyword :any]] :nil]})
  (Operation pull "An entity as a named view selects it, or nothing for no such entity."
    {:signature [:=> [:cat :keyword :int] [:maybe [:map-of :keyword :any]]]})
  (Operation clear! "Forget everything."
    {:signature [:=> [:cat] :nil]}))
