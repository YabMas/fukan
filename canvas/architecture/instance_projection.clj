(ns canvas.architecture.instance-projection
  "Self-spec: the INSTANCE projection (`fukan.canvas.projection.instance`) — the
   print-dual of the INSTANCE surface, `structure-form`'s sibling one stratum
   down: a model node renders back as the def-emitting form that authored it.
   Three renders from the same parts: the faithful data form, a focus rendered
   as authored forms (the textual model explorer), and `check` output with each
   offender quoted as its form. With this, the model — not just the grammar —
   talks back in the language it was written in."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.lens-engine :as lens-engine]
            [canvas.architecture.materialize :as mat]))

(Module projection-instance
  "Render model nodes back as their authored instance forms."
  (Kind Form)                                       ; an instance data form (the print-dual's faithful render) — opaque
  (Kind Text :string)                               ; a formatted render
  (Kind Focus [:or [:vector :int] [:vector :any]])  ; an eid set or datalog clauses (most-specific branch first)
  (Operation instance-form
    "A model node rendered back as its authored instance form (the data dual)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:eid mat/Eid]] Form]})
  (Operation instance-text
    "instance-form, formatted like the authored source (aligned slot map)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:eid mat/Eid]] Text]})
  (Operation focus-text
    "A focus (clauses or eids) rendered as its authored forms — the textual model explorer."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:focus Focus]] Text]
     :delegates [lens-engine/focus-nodes]})
  (Operation violations-text
    "check output with each offender quoted as its authored form, fix-adjacent."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:violations [:vector kernel/Violation]]] Text]}))
