(ns canvas.architecture.ingestion.source
  "Self-spec: fukan's canvas-ingestion subsystem (`fukan.canvas.projection.canvas-source`) — a
   boundary sketch.

   `require-canvas-namespaces!` discovers the canvas namespaces and requires them (interning their
   instance-vars), so the native Cozo build's var-scan can read them; references between instances
   are ordinary var-refs the assembler resolves, so there is no merge/cross-ref pass.
   `canvas-namespaces` is the discovery half exposed on its own. (Require remains an internal.)"
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]))

(Module canvas-source
  "Discover the canvas specs + require them (intern their instance-vars) for the native build."
  (Operation canvas-namespaces "Discover the canvas namespaces on the classpath (the build's input list)."
    {:signature [:=> [:cat] [:vector :any]]
     :performs  [:io]})
  (Operation require-canvas-namespaces!
    "Discover + require the canvas namespaces (intern their instance-vars), returning the ns syms —
     the load step the native Cozo build runs (its var-scan needs the namespaces loaded)."
    {:signature [:=> [:cat] [:vector :any]]
     :performs  [:io :stderr :require :throws]}))
