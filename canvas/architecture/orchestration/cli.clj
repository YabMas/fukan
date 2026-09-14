(ns canvas.architecture.orchestration.cli
  "Self-spec: fukan's non-REPL entry (`fukan.cli`) — what the REPL cockpit does, for a PROGRAM.

   Four verbs, because a reader arrives with four questions: `describe` (what has this project
   DECLARED), `check` (does the code still OBEY it), `report` (how far from obeying is one region
   of it) and `elements` (what are the declared things, as data to join on). The split between
   `check` and `report` is the whole point of having both — `check` decides the model and may be
   scoped by nothing, `report` measures a slice and gates on nothing — so only the verb that
   decides can fail a build.

   A sibling of `core` in the orchestration subsystem, and for the same reason: it composes the
   model lifecycle behind a `-main` and realizes no subject faculty of its own. Its judgement is
   in the two data shapes it hands a consumer that never loaded the model — `findings` turns
   `law/check`'s eid tuples into named ones, and `tally` turns them into counts a migration can
   watch fall."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.law :as cozo-law]
            [canvas.architecture.cozo.query :as cozo-query]
            [canvas.architecture.kernel.lens :as lens]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.projection.instance :as instance]
            [canvas.architecture.orchestration.infra :as infra]
            [canvas.architecture.orchestration.pipeline :as pipeline]))

(Module cli
  "The non-REPL entry — describe a project's declared design, check the code against it, or report
   how far one region of it still has to go."
  (Operation findings
    "`check`'s violations with every offender eid resolved to its name — the shape a consumer
     that never loaded the model can act on. Offenders stay TUPLES: a law binding an edge
     carries both ends, and the second is the half that says what to do."
    {:signature [:=> [:catn [:db :any] [:violations :any]] :any]
     :performs  [:throws]                          ; reaches entity's refusal through offender-label
     :delegates [cozo-law/offender-label]})
  (Operation tally
    "`check`'s violations as COUNTS per law, partitioned against a focus node-set: in scope, out of
     scope, and the rows a node-selection cannot adjudicate at all — the value offenders, where a law
     reports the name it could not resolve rather than an entity. The partition is total and the
     three counts sum to the whole, so a zero reads as a zero rather than as a scope that quietly
     dropped what it could not decide. A nil focus is the whole model.

     Laws are identified by the (structure tag, description) PAIR, not by `:key`: the key is optional
     and most laws carry none, so a report that grouped on it would address a fraction of what ran."
    {:signature [:=> [:catn [:violations :any] [:focus [:maybe [:vector substrate/Eid]]]] :any]})
  (Operation element-rows
    "Every AUTHORED element of the model as a row a consumer joins on: its identity, name and sort,
     a digest of its rendered declaration, its docstring, the ids of the elements each relation slot
     names, and — where its module pairs with code — the namespace and the source file under `src`
     it pairs with. Extracted facts and the reflection meta-grammar are not elements, and a slot
     naming an anonymous value names none. A member takes its module's namespace; an element whose
     module pairs with nothing carries none rather than a guessed one."
    {:signature [:=> [:catn [:db :any] [:opts :map]] :any]
     :performs  [:io :state :throws]               ; reads the model and the file system for the source path
     :delegates [design/declared-nodes instance/instance-form]})
  (Operation -main
    "Entry point: dispatch the verb, build the model under the given spec-dirs, print the
     result — exit 0 satisfied, 1 unsatisfied, 2 undecidable. `report` never answers 1: it
     measures, and only a law it could not evaluate makes it undecidable."
    {:signature [:=> [:catn [:args [:sequential :string]]] :nil]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model pipeline/build-model cozo-law/check lens/focus-nodes
                 design/design-text instance/violations-text findings tally element-rows]}))
