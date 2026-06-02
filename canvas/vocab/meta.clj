(ns canvas.vocab.meta
  "The fukan-on-fukan model's SCHEMA layer — a grammar for describing a data MODEL
   of concepts (a third vocab layer alongside the data layer `shape` and the
   computation layer `op`). Used to model fukan's own kernel reflexively.

   It mirrors `defstructure` IN `defstructure`: a `Concept` is composed of
   `MetaSlot`s; a `MetaSlot` is a named relation with a cardinality to a target
   `Concept` — which is exactly what a defstructure slot is. So describing 'a
   structure has slots; a slot has a cardinality and a target' here is the kernel
   describing itself.

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure ^:value MetaSlot
  "A slot of a Concept — value-identified by its (name, cardinality, target)."
  (slot :name        (one :String))
  (slot :cardinality (one :String))     ; one | some | many | optional | ordered
  (slot :of          (one Concept))     ; the target concept
  (law "a slot's cardinality is one of the known cardinalities"
    :offenders '[?s]
    :where '[[?s :val/cardinality ?c]
             [(clojure.core/contains? #{"one" "some" "many" "optional" "ordered"} ?c) ?ok]
             [(clojure.core/false? ?ok)]]))

(defstructure Concept
  "A concept (type) in a modelled data model — a defstructure structure, a reified
   relation, a scalar type, …. Composed of MetaSlots."
  (slot :doc  (optional :String))
  (slot :slot (many MetaSlot)))
