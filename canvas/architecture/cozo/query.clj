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
  (Operation cvar "A datalog var → its CozoScript name (?e → e)."
    {:signature [:=> [:catn [:t :any]] :string]})
  (Operation vocab-index "Compile the vocabulary's rules once into a name→{:lines :refs} index (+ the synthetic fn-predicate rules)."
    {:signature [:=> [:cat] :any]
     :performs  [:state :throws]                  ; counter/refs atoms + the per-rule compile guard
     :delegates [kstructure/vocab-rules]})
  (Operation compile-body "Compile where-clauses + caller rules → [rule-lines body-str], emitting the reachable vocab rules."
    {:signature [:=> [:catn [:where :any] [:rules :any] [:index :any]] :any]
     :performs  [:state :throws]})
  (Operation q "Run a datalog query over a Cozo db like d/q — relation/collection finds, an :in of $ + optional % (rules) + scalar params. Cells are strings (the triple view)."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:query :any]] :any]
     :performs  [:state :throws]
     :delegates [compile-body vocab-index db/q]})
  (Operation entity "Resolve an eid (string) to its typed attribute map — the d/entity replacement."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:eid :any]] :any]
     :delegates [db/q]}))
