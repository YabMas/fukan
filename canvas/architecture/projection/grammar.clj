(ns canvas.architecture.projection.grammar
  "Self-spec: the GRAMMAR projection (`fukan.canvas.projection.grammar`) — the
   print-dual of the authoring surface (reified Structures render back as map-form
   defstructures; the primer is the live language reference) plus the GRAMMAR-DRIFT
   reading: `unused-structures`, the dead-vocabulary signal. With the grammar
   reflected onto the graph, drift detection extends to the language itself —
   a Structure no instance inhabits is vocabulary the model carries but does not
   speak. (A reading to reason with, not a gate: law-hosts and not-yet-spoken
   grammar are legitimate — the human interprets.)"
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.structure :as kstructure]
            [canvas.architecture.kernel.typing :as typing]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.projection.instance :as instance]))

(Module projection-grammar
  "Render the reified grammar back out: forms, the primer, the correspondence card (registry-direct
   authored form + db-direct live coverage readings), and the grammar-drift reading.
   The form/primer/card readers use the kernel Cozo query layer (`query/q`)."
  (Kind Primer :string)    ; the reference-card string
  (Kind VocabName :string) ; a grammar namespace name
  (Operation correspondence-card
    "The correspondence SEAM rendered as one card: every registered essential `correspond` — its
     authored head/match/map form — plus its live VOCAB-GENERIC coverage readings
     (unrealized/ambiguous) computed over the model db's `corresponds`/`realized-*` rules. Stays
     vocab-agnostic on purpose: the unaccounted-public count needs the fact sort's own `public`
     predicate — the law keyed :correspondence/public-unaccounted owns that, and dev/user.clj
     appends its count after this card."
    {:signature [:=> [:catn [:db substrate/StructureDb]] Primer]
     :performs  [:throws :state]
     :delegates [query/q kstructure/vocab-rules kstructure/all-corresponds]})
  (Operation structure-form
    "A reified Structure rendered back as its canonical map-form defstructure (the print-dual).
     External correspondence renders separately through `correspondence-form`, so both forms are
     valid authoring input."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:eid substrate/Eid]] kstructure/Form]
     :performs  [:throws :state]                ; via the query compiler / render-type
     :delegates [typing/render-type query/q query/entity]})   ; renders refined slot targets + reads the graph
  (Operation correspondence-form
    "A design Structure's external correspondence rendered as canonical, re-authorable `(correspond …)`
     data, or nil when no correspondence has that design sort. Registry-direct (the authored
     head/match/realization-map) — the dual of `structure-form` for the bridge declaration a
     defstructure form omits."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:eid substrate/Eid]] :any]
     :performs  [:throws :state]
     :delegates [query/q kstructure/all-corresponds]})
  (Operation vocabulary-primer
    "One vocabulary rendered as its defstructure forms; {:full? true} keeps whole docstrings —
     a design DOCUMENT rather than a reference card."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:vocab-name VocabName] [:opts [:or :map :nil]]] Primer]
     :performs  [:throws :state]
     :delegates [instance/doc-text]})
  (Operation grammar-primer
    "Every vocabulary in the model — the live language reference, derived not maintained."
    {:signature [:=> [:catn [:db substrate/StructureDb]] Primer]
     :performs  [:throws :state]})
  (Operation unused-structures
    "The grammar-drift reading: reified Structures no instance inhabits — dead
     vocabulary. Excludes the Any wildcard and derivation-inhabited concepts:
     realized-as, and facets reached via includes (found by the loop's first
     run — Connected is spoken, just never directly). Sorted structure names."
    {:signature [:=> [:catn [:db substrate/StructureDb]] [:vector :string]]
     :performs  [:throws :state]}))
