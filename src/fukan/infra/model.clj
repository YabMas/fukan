(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value and offers load / refresh / get. In Plan 1 the loader is
   fixture-only — Plans 2/3/5 swap in real analyzers without changing this API.")

(defonce ^:private state (atom {:model nil :src nil}))

(defn- empty-model
  "Placeholder Model. Replaced in Task 8 by build/empty-model."
  []
  {:primitives {} :edges [] :tags [] :predicates [] :renderers [] :artifacts {}})

(defn load-model
  "Build (or reload) the Model for the given src path. In Plan 1 this returns
   an empty Model; later plans wire real analyzers through here."
  [src]
  (println "Loading model from" src "(Plan 1 fixture-only; no analyzers yet)")
  (let [m (empty-model)]
    (reset! state {:model m :src src})
    m))

(defn get-model
  "Current Model, or nil if not loaded."
  []
  (:model @state))

(defn get-src
  "Current src path, or nil."
  []
  (:src @state))

(defn refresh-model
  "Rebuild from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))
