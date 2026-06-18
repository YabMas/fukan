(ns canvas.architecture.kernel.malli
  "Self-spec: the MALLI type dialect (`fukan.dialect.malli`) — a boundary sketch. The first concrete
   dialect of `typing`'s neutral plug-point: a Schema subgraph → a malli data-form (`render`),
   refined-slot value checking (`valid?`, the one bridge that runs the malli library), signature
   adherence (`sigs-adhere?`), and a type form → its content-deduped Schema subgraph (`reflect`,
   which emits that subgraph via the assembler). It builds its value subgraphs on the kernel's value
   machinery (`value-literal->iv`) — now declared, after exposing that machinery as kernel API. That
   widening (one of several) is itself a signal: the kernel<->typing-plugin seam wants a deliberate
   SPI rather than piecemeal exposure (see the kernel<->dialect-boundary note; parked)."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.assemble :as assemble]))

(Module malli
  "The malli runtime dialect — render / valid? / reflect / sigs-adhere? bridges to the type plug-point."
  (Operation render "A Schema subgraph at an eid → a malli data-form."
    {:signature [:=> [:catn [:db :any] [:eid :any]] :any]})
  (Operation valid? "A refined-slot type-form ⊨ a scalar value (runs the malli library)."
    {:signature [:=> [:catn [:form :any] [:value :any]] :boolean]})
  (Operation reflect "A malli type form → its content-deduped Schema subgraph."
    {:signature [:=> [:catn [:form :any]] :any]
     :delegates [kernel/value-literal->iv assemble/emit-instances]})   ; build the value subgraph via the kernel + assembler
  (Operation sigs-adhere? "Whether two malli function-schemas adhere (out-equality + in-sequence equality)."
    {:signature [:=> [:catn [:a :any] [:b :any]] :boolean]}))
