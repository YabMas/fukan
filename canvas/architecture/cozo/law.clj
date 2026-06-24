(ns canvas.architecture.cozo.law
  "Self-spec: `fukan.cozo.law` — the general law engine on Cozo. Compiles a defstructure
   law's datalog (offenders + where, the same laws `structure/check` runs, read via the
   kernel) into CozoScript over the unified `triple` view and runs it — the keystone
   that replaces datascript's `check` at cut-over. Built alongside datascript."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.kernel.structure :as kstructure]))

(Module cozo-law
  "Compile defstructure laws' datalog → CozoScript over the Cozo substrate and run
   them — the Cozo analog of structure/check."
  (Operation compile-law
    "Compile a law's offender query (offenders + where, scope-clause prepended for a direct tag) → a CozoScript program. String compilation over a local counter atom (:state) for unique not-join helper names; throws on an unsupported form."
    {:signature [:=> [:catn [:law :any] [:direct-tags :any]] :string]
     :performs  [:state :throws]})
  (Operation check-structural
    "Run every currently-compilable law over the Cozo db, returning offenders (or :unsupported for laws whose form isn't compiled yet) — the Cozo analog of structure/check."
    {:signature [:=> [:catn [:cdb db/CozoDb]] :any]
     :performs  [:state :throws]
     :delegates [kstructure/all-structures db/q]}))
