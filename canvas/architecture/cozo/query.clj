(ns canvas.architecture.cozo.query
  "Self-spec: `fukan.cozo.query` — the general datalog→CozoScript query compiler + entity
   accessor: the kernel query primitive on Cozo (the `d/q` + `d/entity` replacement at
   cut-over). It owns the clause/rule compiler (datom / not / not-join / or-join /
   predicates / rule-calls + the vocab-rule index and reachability closure); the law engine
   and the ported readers build on it. `q` compiles a datalog query over the unified
   all-string `triple` view (eids/values come back as strings); `entity` resolves an eid to
   its typed attribute map from the typed buckets."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.kernel.structure :as kstructure]))

(Module cozo-query
  "The Cozo query primitive — compile a datalog query/where to CozoScript and run it; resolve
   an eid to its attributes. The clause/rule compiler the law engine and readers share."
  (Operation register-predicate-port! "Register a vocab fn-predicate's CozoScript port (sym, builder, synthetic rule defs) into the compiler's atom-backed registry. Vocab calls this at load (the typing-plug-point pattern)."
    {:signature [:=> [:catn [:sym :symbol] [:builder :any] [:synthetic :map]] :nil]
     :performs  [:state]})
  (Operation cvar "A datalog var → its CozoScript name (?e → e)."
    {:signature [:=> [:catn [:t :any]] :string]})
  (Operation vocab-index "Compile the vocabulary's rules once into a name→{:lines :refs} index (+ the synthetic fn-predicate rules)."
    {:signature [:=> [:cat] :any]
     :performs  [:throws]                          ; reaches the compiler's unsupported-form throw
     :delegates [kstructure/vocab-rules]})
  (Operation compile-body "Compile where-clauses + caller rules + outer-scope vars (find vars / law offenders — they count toward inline-measure grouping inference) → [rule-lines body-str], emitting the reachable vocab rules. A PURE compiler (content-named helpers, threaded wildcard counter, lifted-measure aux rules)."
    {:signature [:=> [:catn [:where :any] [:rules :any] [:index :any] [:outer-vars :any]] :any]
     :performs  [:throws]})
  (Operation q "Run a datalog query over a Cozo db like d/q — relation/collection finds, an :in of $ + optional % (rules) + scalar params. Cells are strings (the triple view)."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:query :any]] :any]
     :performs  [:throws]
     :delegates [compile-body vocab-index db/q]})
  (Operation entity "Resolve an eid (string) to its typed attribute map — the d/entity replacement."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:eid :any]] :any]
     :delegates [db/q]})
  (Operation violation-names "The :entity/name of every offender of the law keyed k — the read-side pairing of the kernel's violations-of (which returns eids), resolving each through entity. The one home for the recurring worklist-reader shape."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:k :keyword]] :any]
     :delegates [entity kstructure/violations-of]}))
