(ns demos.workflow.model.fulfilment
  "An order-fulfilment workflow modelled with the control-flow vocabulary:

     receive → validate → ⟨fork⟩ → charge-card  ┐
                                  → reserve-stock ┘→ ship → notify → done

   `validate` FORKS into two parallel branches (charge-card, reserve-stock); both
   branches JOIN at `ship` (fan-in). `done` is the terminal step. Authored
   top-down, so every :next is a forward reference resolved at assemble time
   via var capture."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.workflow.vocab.core :refer [Step Workflow]]))

(declare validate charge-card reserve-stock ship notify done-step)

(def receive       (Step "receive"       (next validate)))
(def validate      (Step "validate"      (next charge-card reserve-stock)))  ; FORK — fan-out
(def charge-card   (Step "charge-card"   (next ship)))
(def reserve-stock (Step "reserve-stock" (next ship)))                       ; JOIN at ship — fan-in
(def ship          (Step "ship"          (next notify)))
(def notify        (Step "notify"        (next done-step)))
(def done-step     (Step "done"))                                             ; terminal

(def order-fulfilment
  (Workflow "order-fulfilment"
    (start receive)
    (step receive) (step validate) (step charge-card)
    (step reserve-stock) (step ship) (step notify) (step done-step)))

(defn build [] (a/assemble ['demos.workflow.model.fulfilment]))
