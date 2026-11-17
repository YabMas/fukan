(ns canvas.architecture.projection.reference
  "Self-spec: the SELECTION REFERENCE (`fukan.canvas.projection.reference`) — what a selection
   may say, written for the model it will be run against.

   A selection narrows `describe`, `report`, `elements` and a view, and until this module the
   only account of its language was the source of three namespaces. It hides which clause forms
   are worth showing, how a worked selection is built from a model's own sorts and relations, and
   the wording. It names no sort and no relation: the examples are made from what the model
   declares, so a project in a vocabulary fukan does not ship is handed selections that run.

   Every selection it prints was evaluated against the model to print it, with the number of
   nodes it selected beside it. A reference cannot show what the engine refuses."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.lens :as lens]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.projection.view :as view]))

(Module projection-reference
  "State the selection language for one model, with selections that were run against it."
  (Operation selection-reference
    "The selection reference for `db`: the clause forms a selection may use, each shown by a
     selection that ran; the attributes this model's declared nodes carry; the sorts and the
     relations it offers; the rule that a view draws a link only between two selected nodes; and
     worked selections built from the model's own names — one sort, a union of sorts, a grouping
     with its members, nodes by id prefix with their members — each with how many nodes it
     selected."
    {:signature [:=> [:catn [:db substrate/StructureDb]] instance/Text]
     :performs  [:throws :state]
     :delegates [design/declared-nodes view/views lens/focus-nodes query/q]}))
