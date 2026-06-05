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

(def receive       (Step (next validate)))
(def validate      (Step (next charge-card reserve-stock)))  ; FORK — fan-out
(def charge-card   (Step (next ship)))
(def reserve-stock (Step (next ship)))                       ; JOIN at ship — fan-in
(def ship          (Step (next notify)))
(def notify        (Step (next done-step)))
(def done-step     (Step "done"))                                             ; terminal

(def order-fulfilment
  (Workflow
    (start receive)
    (step receive) (step validate) (step charge-card)
    (step reserve-stock) (step ship) (step notify) (step done-step)))

(defn build [] (a/assemble ['demos.workflow.model.fulfilment]))
