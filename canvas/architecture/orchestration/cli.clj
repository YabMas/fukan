(ns canvas.architecture.orchestration.cli
  "Self-spec: fukan's non-REPL entry (`fukan.cli`) — what the REPL cockpit does, for a PROGRAM.

   Two verbs, because a reader arrives with two questions: `describe` (what has this project
   DECLARED) and `check` (does the code still OBEY it). A sibling of `core` in the orchestration
   subsystem, and for the same reason: it composes the model lifecycle behind a `-main` and
   realizes no subject faculty of its own. Its one piece of judgement is the rendering —
   `findings` turns `law/check`'s eid tuples into named ones, which is what makes a violation
   legible to a program that never loaded the model."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.law :as cozo-law]
            [canvas.architecture.cozo.query :as cozo-query]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.orchestration.infra :as infra]
            [canvas.architecture.orchestration.pipeline :as pipeline]))

(Module cli
  "The non-REPL entry — describe a project's declared design, or check the code against it."
  (Operation findings
    "`check`'s violations with every offender eid resolved to its name — the shape a consumer
     that never loaded the model can act on. Offenders stay TUPLES: a law binding an edge
     carries both ends, and the second is the half that says what to do."
    {:signature [:=> [:catn [:db :any] [:violations :any]] :any]
     :delegates [cozo-query/entity]})
  (Operation -main
    "Entry point: dispatch the verb, build the model under the given spec-dirs, print the
     result — exit 0 satisfied, 1 unsatisfied, 2 undecidable."
    {:signature [:=> [:catn [:args [:sequential :string]]] :nil]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model pipeline/build-model cozo-law/check
                 design/design-text instance/violations-text findings]}))
