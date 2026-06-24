(ns canvas.architecture.cozo.law
  "Self-spec: `fukan.cozo.law` — the general law engine on Cozo. Compiles a defstructure
   law's datalog (offenders + where, the same laws `structure/check` runs, read via the
   kernel) into CozoScript over the unified `triple` view and runs it — the keystone
   that replaces datascript's `check` at cut-over. Built alongside datascript."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.kernel.structure :as kstructure]
            [canvas.architecture.kernel.typing :as ktyping]))

(Module cozo-law
  "Compile defstructure laws' datalog → CozoScript over the Cozo substrate and run
   them — the Cozo analog of structure/check."
  (Operation compile-law
    "Compile a law's offender query (offenders + its :rules + where, scope-clause prepended for a direct tag) → a CozoScript program: the vocab rules in its reference closure (from the compiled-rule `index`), its own rules, helper rules, then the `?` entry. String compilation over local counter/refs atoms (:state); throws on an unsupported form."
    {:signature [:=> [:catn [:law :any] [:direct-tags :any] [:index :any]] :string]
     :performs  [:state :throws]})
  (Operation check-structural
    "Run every law over the Cozo db, returning offenders (or :unsupported for laws whose form isn't compiled yet). Compiles each law to CozoScript, except the scalar TYPE-CHECK laws, which run a HYBRID — Cozo finds each instance's leaf value, typing/value-valid? (malli) checks it. The Cozo analog of structure/check."
    {:signature [:=> [:catn [:cdb db/CozoDb]] :any]
     :performs  [:state :throws]
     :delegates [kstructure/all-structures kstructure/direct-scope-tags kstructure/vocab-rules
                 ktyping/value-valid? db/q]})
  (Operation check
    "Run every law over the Cozo db and return its VIOLATIONS — the structure/check-shaped drift list (the violation-only view of check-structural). A law whose form isn't compilable contributes nothing (the law-engine tests guarantee fukan's own laws all compile). The drop-in Cozo replacement for structure/check."
    {:signature [:=> [:catn [:cdb db/CozoDb]] :any]
     :performs  [:state :throws]                    ; reaches :state/:throws through check-structural → compile-law's atoms
     :delegates [check-structural]}))
