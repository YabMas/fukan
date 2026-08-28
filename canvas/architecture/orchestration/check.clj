(ns canvas.architecture.orchestration.check
  "Self-spec: fukan's non-REPL check entry (`fukan.check`) — the CLI that reports a project's
   violations as DATA rather than as prose for a human.

   A sibling of `core` in the orchestration subsystem, and for the same reason: it composes the
   model lifecycle behind a `-main` and realizes no subject faculty of its own. Its one piece of
   judgement is the rendering — `findings` turns `law/check`'s eid tuples into named ones, which
   is what makes a violation legible to a program that never loaded the model."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.law :as cozo-law]
            [canvas.architecture.cozo.query :as cozo-query]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.orchestration.infra :as infra]))

(Module check
  "The machine-readable check entry — build a project's model, report its violations as data."
  (Operation findings
    "`check`'s violations with every offender eid resolved to its name — the shape a consumer
     that never loaded the model can act on. Offenders stay TUPLES: a law binding an edge
     carries both ends, and the second is the half that says what to do."
    {:signature [:=> [:catn [:db :any] [:violations :any]] :any]
     :delegates [cozo-query/entity]})
  (Operation -main
    "Entry point: build the held Model under the given spec-dirs, check it, and print the report
     — exit 0 satisfied, 1 unsatisfied, 2 undecidable."
    {:signature [:=> [:catn [:args [:sequential :string]]] :nil]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model cozo-law/check instance/violations-text findings]}))
