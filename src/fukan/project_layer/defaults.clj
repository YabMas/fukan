(ns fukan.project-layer.defaults
  "fukan-on-fukan's own project registry. The self-referential case:
   fukan's source lives under the `fukan.*` namespace, so a canvas
   module-coord such as `infra.model` maps to the Clojure ns
   `fukan.infra.model`. The registry's :root-prefix carries that
   prefix; no custom type overrides and no idioms in Plan 5's MVP."
  (:require [fukan.project-layer.registry :as r]))

(defn fukan-on-fukan
  "Project registry for fukan analyzing itself. :root-prefix is
   `\"fukan\"` because fukan's code lives at fukan.<module-coord>."
  []
  (r/with-root-prefix (r/make-registry) "fukan"))
