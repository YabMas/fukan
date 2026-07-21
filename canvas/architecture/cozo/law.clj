(ns canvas.architecture.cozo.law
  "Self-spec: `fukan.cozo.law` — the general law engine on Cozo, and the home of `check`. Compiles a
   defstructure law's datalog (offenders + where, read from the kernel it DEFINES via `laws-of`) into
   CozoScript over the unified `triple` view and runs it. `check` (+ its readers `violations-of`/
   `violation-names`) lives HERE, in the engine, because it is EVALUATION — the kernel owns DEFINITION.
   The dependency runs one way (engine → kernel), so there is no `structure ↔ law` cycle and no registry
   (the old hollow-kernel-`check` + `register-check-engine!` indirection is retired)."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.cozo.query :as cquery]
            [canvas.architecture.kernel.structure :as kstructure]
            [canvas.architecture.kernel.typing :as ktyping]))

(Module cozo-law
  "Compile defstructure laws' datalog → CozoScript (via the cozo-query compiler) over the
   Cozo substrate and run them — the Cozo analog of structure/check."
  (Operation compile-law
    "Compile a law's offender query (offenders + its :rules + where, scope-clause prepended for a direct/facet tag) → a CozoScript program via the query compiler (compile-body emits the rules in its closure), then the `?` entry. Throws on an unsupported form."
    {:signature [:=> [:catn [:law :any] [:direct-tags :any] [:index :any]] :string]
     :performs  [:state :throws]
     :delegates [cquery/compile-body cquery/cvar]})
  (Operation check-structural
    "Run every law over the Cozo db, returning offenders (or :unsupported for laws whose form isn't compiled yet). Compiles each law to CozoScript, except the scalar TYPE-CHECK laws, which run a HYBRID — Cozo finds each instance's leaf value, typing/value-valid? (malli) checks it. The Cozo analog of structure/check."
    {:signature [:=> [:catn [:cdb db/CozoDb]] :any]
     :performs  [:state :throws]
     :delegates [kstructure/all-structures kstructure/direct-scope-tags cquery/vocab-index
                 ktyping/value-valid? db/q]})
  (Operation check
    "Run every law over the Cozo db and return its VIOLATIONS — the drift list (the violation-only view of check-structural). THE check: it evaluates the laws the kernel defines. Fails closed when any law is unsupported, because an unevaluated constraint cannot establish satisfaction."
    {:signature  [:=> [:catn [:cdb db/CozoDb]] :any]
     :performs   [:state :throws]                    ; reaches :state/:throws through check-structural → compile-law's atoms
     :delegates  [check-structural]})
  (Operation violations-of
    "The offender eids of the law keyed k — the generic reader behind every worklist fn (filters check by the law's stable :key)."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:k :keyword]] :any]
     :performs  [:state :throws]                     ; reaches check's :state/:throws
     :delegates [check]})
  (Operation violation-names
    "The :entity/name of every offender of the law keyed k — violations-of eids resolved through the query layer's entity. The one home for the recurring worklist-reader shape."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:k :keyword]] [:set :string]]
     :performs  [:state :throws]                     ; reaches check's :state/:throws (via violations-of)
     :delegates [violations-of cquery/entity]}))
