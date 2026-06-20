(ns canvas.architecture.kernel.malli
  "Self-spec: the MALLI type dialect (`fukan.dialect.malli`) — a boundary sketch. The first concrete
   dialect of `typing`'s neutral plug-point: a Schema subgraph → a malli data-form (`render`),
   refined-slot value checking (`valid?`, the one bridge that runs the malli library), and signature
   adherence (`sigs-adhere?`). A self-contained LEAF — it delegates to NOTHING (it depends only on the
   malli library + datascript). Reflection (type form → its content-deduped Schema subgraph) is NOT a
   dialect bridge: the kernel's `typing/reflect-type` does the building, driven by the dialect's
   registered value-structure tag (`:reflect-tag`). That is the deliberate SPI that closed the parked
   kernel<->dialect seam — the dialect no longer reaches into kernel value machinery."
  (:require [lib.code :refer [Operation Module]]))

(Module malli
  "The malli runtime dialect — render / valid? / sigs-adhere? bridges to the type plug-point. A leaf."
  (Operation render "A Schema subgraph at an eid → a malli data-form."
    {:signature [:=> [:catn [:db :any] [:eid :any]] :any]})
  (Operation valid? "A refined-slot type-form ⊨ a scalar value (runs the malli library)."
    {:signature [:=> [:catn [:form :any] [:value :any]] :boolean]})
  (Operation sigs-adhere? "Whether two malli function-schemas adhere (out-equality + in-sequence equality)."
    {:signature [:=> [:catn [:a :any] [:b :any]] :boolean]}))
