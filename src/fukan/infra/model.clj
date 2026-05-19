(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value (per fukan.model.build/Model) and offers load /
   refresh / get. Plan 3b wires the top-level multi-extension pipeline
   (Allium + Boundary) in here; calling `load-model` on a source root
   composes both extension parsers."
  (:require [fukan.model.pipeline :as pipeline]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn load-model
  "Build (or reload) the Model for the given src path by invoking the
   top-level multi-extension pipeline (Allium + Boundary). Closes Plan 3b."
  [src]
  (println "Loading model from" src "(Allium + Boundary — Plan 3b)")
  (let [m (pipeline/load-source src)]
    (reset! state {:model m :src src})
    (println "Loaded:" (count (:primitives m)) "primitives,"
                       (count (:edges m)) "edges,"
                       (count (:tag-apps m)) "tag applications")
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
