(ns canvas.principles.deep-modules
  "PRINCIPLE — modules should be deep (Ousterhout, APoSD ch.4).

   A module's value is functionality per unit of interface: a small surface over a large
   implementation is deep (good); a wide surface over little implementation is shallow
   (interface tax without abstraction). DELIBERATELY JUDGMENT-ONLY — no law: depth is a
   design call, so the principle terminates in the `Depth` reading (interface size vs
   implementation size per module, shallowest first, rendered from inline measures).
   Note the model asymmetry: `:exposes` is an AUTHORED-side concept; extraction attaches ops
   via `:child` and marks privacy as a value, so the code-side interface proxy is
   `(not private)`."
  (:require [fukan.canvas.core.lens :refer [Projection]]))

;; the principle's JUDGMENT surface — rendered by materialize/render-finding "Depth"
(Projection Depth
  "Module depth — interface size against implementation size, shallowest first (a reading)."
  {:select '[(Module ?n)]})
