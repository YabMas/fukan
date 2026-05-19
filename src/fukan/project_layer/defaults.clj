(ns fukan.project-layer.defaults
  "fukan-on-fukan's own project registry. The self-referential case:
   fukan's source lives at namespaces matching its Allium module coords
   exactly (root-prefix is empty), with no custom type overrides and
   no idioms in Plan 5's MVP scope."
  (:require [fukan.project-layer.registry :as r]))

(defn fukan-on-fukan
  "Project registry for fukan analyzing itself. Identity registry."
  []
  (r/make-registry))
