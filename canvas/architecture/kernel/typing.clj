(ns canvas.architecture.kernel.typing
  "Self-spec: the TYPE-DIALECT PLUG-POINT (`fukan.canvas.core.typing`) — a boundary sketch. The third
   kernel plug-point (alongside the extractor and the render multimethod): a slot for a project's TYPE
   dialect, a map of bridge fns (render / parse / adheres? / valid? / reflect). The kernel itself
   consumes it — a refined slot target compiles to a law that checks values through the dialect — so
   it is kernel-owned, but the registry is language-NEUTRAL: the kernel never interprets a type form.
   It dispatches to the registered dialect; it has no cross-module dependencies of its own."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]))

(Module typing
  "The type-dialect plug-point — register a dialect; render / check / reflect types through it."
  (Operation register-type-dialect! "Install a project's type dialect (the bridge-fn map)."
    {:signature [:=> [:catn [:dialect :any]] :any]})
  (Operation render-type "Render a type subgraph at an eid to a code-form, via the dialect."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:eid :any]] :any]})
  (Operation type-adheres? "Whether a model type-form adheres to a realized code type-form, via the dialect."
    {:signature [:=> [:catn [:model-form :any] [:code-form :any]] :boolean]})
  (Operation reflect-type "A type form → its content-deduped subgraph, via the dialect."
    {:signature [:=> [:catn [:form :any]] :any]}))
