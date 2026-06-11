(ns canvas.architecture.grammar-projection
  "Self-spec: the GRAMMAR projection (`fukan.canvas.projection.grammar`) — the
   print-dual of the authoring surface (reified Structures render back as map-form
   defstructures; the primer is the live language reference) plus the GRAMMAR-DRIFT
   reading: `unused-structures`, the dead-vocabulary signal. With the grammar
   reflected onto the graph, drift detection extends to the language itself —
   a Structure no instance inhabits is vocabulary the model carries but does not
   speak. (A reading to reason with, not a gate: law-hosts and not-yet-spoken
   grammar are legitimate — the human interprets.)"
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.materialize :as mat]))

(Module projection-grammar
  "Render the reified grammar back out: forms, the primer, and the grammar-drift reading."
  (Kind Form)        ; a defstructure data form (the print-dual's faithful render)
  (Kind Primer)      ; the reference-card string
  (Kind VocabName)   ; a grammar namespace name
  (Operation structure-form
    "A reified Structure rendered back as its map-form defstructure (the print-dual)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:eid mat/Eid]] Form]})
  (Operation vocabulary-primer
    "One vocabulary rendered as its defstructure forms."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:vocab-name VocabName]] Primer]})
  (Operation grammar-primer
    "Every vocabulary in the model — the live language reference, derived not maintained."
    {:signature [:=> [:catn [:db kernel/StructureDb]] Primer]})
  (Operation unused-structures
    "The grammar-drift reading: reified Structures no instance inhabits — dead
     vocabulary. Excludes the Any wildcard and derivation-inhabited concepts:
     realized-as, and facets reached via includes (found by the loop's first
     run — Connected is spoken, just never directly). Sorted structure names."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:vector :string]]}))
