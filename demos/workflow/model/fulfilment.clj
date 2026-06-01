(ns demos.workflow.model.fulfilment
  "An order-fulfilment workflow modelled with the control-flow vocabulary:

     receive → validate → ⟨fork⟩ → charge-card  ┐
                                  → reserve-stock ┘→ ship → notify → done

   `validate` FORKS into two parallel branches (charge-card, reserve-stock); both
   branches JOIN at `ship` (fan-in). `done` is the terminal step. Authored
   top-down, so every :next is a forward reference resolved by within-module's
   second pass."
  (:require [fukan.canvas.core.structure :as s]
            [demos.workflow.vocab.core :refer [Step Workflow]]))

(defn build []
  (s/with-structures
    (s/within-module "fulfilment"
      (Step "receive"       (next validate))
      (Step "validate"      (next charge-card reserve-stock))  ; FORK — fan-out
      (Step "charge-card"   (next ship))
      (Step "reserve-stock" (next ship))                       ; JOIN at ship — fan-in
      (Step "ship"          (next notify))
      (Step "notify"        (next done))
      (Step "done")                                            ; terminal
      (Workflow "order-fulfilment"
        (start receive)
        (step receive) (step validate) (step charge-card)
        (step reserve-stock) (step ship) (step notify) (step done)))))
