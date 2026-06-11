(ns canvas.vocabulary.meta
  "The fukan-on-fukan model's SCHEMA layer — a grammar for describing a data MODEL
   of concepts (a third vocab layer alongside the data layer `shape` and the
   computation layer `op`). Used to model fukan's own kernel reflexively.

   It mirrors `defstructure` IN `defstructure`: a `Concept` is composed of
   `MetaSlot`s; a `MetaSlot` is a named relation with a cardinality to a target
   `Concept` — which is exactly what a defstructure slot is. So describing 'a
   structure has slots; a slot has a cardinality and a target' here is the kernel
   describing itself.

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; refined slot targets ([:enum …]) check through the malli type dialect
            [lib.type.malli]))

(defstructure ^:value MetaSlot
  "A slot of a Concept — value-identified by its (name, cardinality, target). Mirrors a real
   slot's target: a structure-REF (`:of` another Concept) OR a leaf SCALAR type (`:scalar`).
   A field declares one or the other — exactly as the core distinguishes a symbol (tag ref)
   from a keyword (scalar type), so no scalar needs a Concept node of its own."
  {:name        :String
   :cardinality [:enum "one" "optional" "many" "some" "set"]
   :of          [:? Concept]                                    ; a ref target (another Concept), or
   :scalar      [:? [:enum "Keyword" "String" "Int" "Bool"]]})  ; a leaf scalar type

(defstructure Concept
  "A concept (type) in a modelled data model — a defstructure structure, a reified
   relation, a scalar type, …. Composed of MetaSlots."
  {:slot [:* MetaSlot]})
