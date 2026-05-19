(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value (per fukan.model.build/Model) and offers load /
   refresh / get. `load-model` invokes the top-level multi-extension
   pipeline — Allium + Boundary parse, Phase 4 structural validation,
   Phase 5 constraint evaluation, Phase 6 Clojure Target Analyzer."
  (:require [fukan.model.pipeline :as pipeline]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn load-model
  "Build (or reload) the Model for the given src path by invoking the
   top-level multi-extension pipeline (Phases 1-6)."
  [src]
  (println "Loading model from" src "(Phases 1-6)")
  (let [m (pipeline/load-source src)]
    (reset! state {:model m :src src})
    (println "Loaded:" (count (:primitives m)) "primitives,"
                       (count (:artifacts m)) "artifacts,"
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

(defn set-model-for-test!
  "Test helper — directly sets the model atom. Never call from production code."
  [m]
  (reset! state {:model m :src "test"}))
