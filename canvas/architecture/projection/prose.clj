(ns canvas.architecture.projection.prose
  "Self-spec: the PROSE projection (`fukan.canvas.projection.prose`) — the declarations as
   sentences rather than as the forms that authored them.

   A form dual renders the AUTHOR's view; a reader who has to OBEY a design wants to be told
   what the rule is, not to infer it from a quantifier vector. That reader is usually an agent
   now, and an inference it makes cheaply is still one it can make wrongly.

   It queries nothing: every operation is a pure function of the data forms `structure-form`
   and `instance-form` already produce, which is what keeps the two views from drifting into
   two different designs."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]))

(Module projection-prose
  "Render the declarations as prose — pure functions over the form duals' output."
  (Operation type-phrase
    "One slot's type expression as a phrase: what may fill it, and how many. An unrecognised
     shape renders as itself rather than as an approximation."
    {:signature [:=> [:catn [:type-expr :any]] :string]})
  (Operation structure-prose
    "One defstructure data form as prose: the concept, what every instance carries, and the
     rules that must hold of it — law DESCRIPTIONS, never law bodies."
    {:signature [:=> [:catn [:form :any]] :string]
     :delegates [type-phrase]})
  (Operation instance-prose
    "One instance data form as an entry: its name, what it is for, and what it declares."
    {:signature [:=> [:catn [:form :any]] :string]}))
