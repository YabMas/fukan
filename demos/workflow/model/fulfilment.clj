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

(Step receive       {:next [validate]})
(Step validate      {:next [charge-card reserve-stock]})  ; FORK — fan-out
(Step charge-card   {:next [ship]})
(Step reserve-stock {:next [ship]})                       ; JOIN at ship — fan-in
(Step ship          {:next [notify]})
(Step notify        {:next [done-step]})
(Step ^{:name "done"} done-step)                          ; terminal

(Workflow order-fulfilment
  {:start receive
   :step  [receive validate charge-card reserve-stock ship notify done-step]})

(defn build [] (a/assemble ['demos.workflow.model.fulfilment]))
