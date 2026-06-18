(ns canvas.architecture.kernel.malli
  "Self-spec: the MALLI type dialect (`fukan.dialect.malli`) — a boundary sketch. The first concrete
   dialect of `typing`'s neutral plug-point: a Schema subgraph → a malli data-form (`render`),
   refined-slot value checking (`valid?`, the one bridge that runs the malli library), signature
   adherence (`sigs-adhere?`), and a type form → its content-deduped Schema subgraph (`reflect`,
   which emits that subgraph via the assembler). It builds its value subgraphs on the kernel's value
   machinery — that dependency reaches kernel internals the boundary sketch does not yet expose, so it
   is not declared here (a pending API decision), unlike the assembler dependency, which is."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.assemble :as assemble]))

(Module malli
  "The malli runtime dialect — render / valid? / reflect / sigs-adhere? bridges to the type plug-point."
  (Operation render "A Schema subgraph at an eid → a malli data-form."
    {:signature [:=> [:catn [:db :any] [:eid :any]] :any]})
  (Operation valid? "A refined-slot type-form ⊨ a scalar value (runs the malli library)."
    {:signature [:=> [:catn [:form :any] [:value :any]] :boolean]})
  (Operation reflect "A malli type form → its content-deduped Schema subgraph."
    {:signature [:=> [:catn [:form :any]] :any]
     :delegates [assemble/emit-instances]})     ; emits the Schema value subgraph via the assembler
  (Operation sigs-adhere? "Whether two malli function-schemas adhere (out-equality + in-sequence equality)."
    {:signature [:=> [:catn [:a :any] [:b :any]] :boolean]}))
