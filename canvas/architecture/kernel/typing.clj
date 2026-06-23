(ns canvas.architecture.kernel.typing
  "Self-spec: the TYPE-DIALECT PLUG-POINT (`fukan.canvas.core.typing`) — a boundary sketch. The third
   kernel plug-point (alongside the extractor and the render multimethod): a slot for a project's TYPE
   dialect, a map of bridge fns + the dialect's value-structure tag (render / parse / adheres? / valid?
   / reflect-tag). The kernel itself consumes it — a refined slot target compiles to a law that checks
   values through the dialect — so it is kernel-owned, but the registry is language-NEUTRAL: the kernel
   never interprets a type form. It dispatches to the registered dialect for render/check/adherence; for
   REFLECTION it does the building itself on the kernel value machinery (driven by the dialect's
   `:reflect-tag`), so a dialect bridge never reaches back into the kernel — the deliberate SPI."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.assemble :as assemble]))

(Module typing
  "The type-dialect plug-point — register a dialect; render / check / reflect types through it."
  (Operation register-type-dialect! "Install a project's type dialect (the bridge-fn map + :reflect-tag)."
    {:signature [:=> [:catn [:dialect :any]] :any]
     :performs  [:state]})
  (Operation render-type "Render a type subgraph at an eid to a code-form, via the dialect."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:eid :any]] :any]})
  (Operation type-adheres? "Whether a model type-form adheres to a realized code type-form, via the dialect."
    {:signature [:=> [:catn [:model-form :any] [:code-form :any]] :boolean]})
  (Operation dialect-type-tag "The registered dialect's value-structure tag — how consumers recognize a reflected type value."
    {:signature [:=> [:cat] :any]})
  (Operation reflect-type "A type form → its content-deduped subgraph: the kernel builds via the dialect's :reflect-tag."
    {:signature [:=> [:catn [:form :any]] :any]
     :performs  [:throws]
     :delegates [kernel/value-literal->iv substrate/value-content-key assemble/emit-instances]})  ; kernel does the building (the SPI)
  (Operation parse-type "A code-form type → entity-maps, via the dialect's :parse bridge (the inverse of render-type)."
    {:signature [:=> [:catn [:form :any]] :any]}))
