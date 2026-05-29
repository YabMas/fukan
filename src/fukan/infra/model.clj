(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value (per fukan.model.build/Model) and offers load /
   refresh / get. `load-model` invokes the top-level multi-extension
   pipeline — Allium + Boundary parse, Phase 4 structural validation,
   Phase 5 constraint evaluation, Phase 6 Clojure Target Analyzer."
  (:require [fukan.model.pipeline :as pipeline]))

(defonce ^:private state (atom {:model nil :canvas-db nil :src nil}))

(defn load-model
  "Build (or reload) the Model for the given src path by invoking the
   top-level multi-extension pipeline (Phases 1-6). Retains the unified
   Phase-0 canvas Datascript db alongside the projected Model so trust/weigh
   queries can reuse it (see get-canvas-db)."
  [src]
  (println "Loading model from" src "(Phases 1-6)")
  (let [{:keys [model canvas-db]} (pipeline/build-model src)]
    (reset! state {:model model :canvas-db canvas-db :src src})
    (println "Loaded:" (count (:primitives model)) "primitives,"
                       (count (:artifacts model)) "artifacts,"
                       (count (:edges model)) "edges,"
                       (count (:tag-apps model)) "tag applications")
    model))

(defn get-model
  "Current Model, or nil if not loaded."
  []
  (:model @state))

(defn get-canvas-db
  "Current unified Phase-0 canvas Datascript db, or nil if not loaded.
   Callers that need the raw canvas substrate (trust/weigh queries, Layer-A
   projection) reuse this instead of rebuilding the db per call; nil signals
   no model is loaded, so the caller falls back to a fresh build."
  []
  (:canvas-db @state))

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
