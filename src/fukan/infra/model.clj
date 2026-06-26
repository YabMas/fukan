(ns fukan.infra.model
  "Model lifecycle: hold the structure substrate db (the model — design decision (ii)),
   offering load / refresh / get. `load-model` invokes the build pipeline, which ingests the
   defstructure canvas specs into one native Cozo structure substrate (merging the extracted
   code when given a source tree). The held Cozo db is closed on each reload.

   This is also fukan-on-itself's composition root: it registers fukan's custom code FACT
   extractor (the Clojure extractor over fukan's `src/`) at the `fukan.model.extraction`
   plug-point. The type dialect needs no wiring here — `canvas.vocab.type` self-registers the
   full malli dialect when it loads (required below to guarantee it is); `fukan.cozo.law` is
   loaded so `(structure/check model)` runs the Cozo law engine."
  (:require [canvas.vocab.type]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as cozo-db]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo backend at structure's
            ;; check-engine plug-point, so `(structure/check model)` runs the Cozo law engine
            [fukan.cozo.law]
            ;; loaded for its side-effect: registers the Coverage law (a src ns, so it does
            ;; not auto-discover like canvas/**) — its convention binding lives in canvas/instruments
            [fukan.canvas.core.coverage]
            [canvas.vocab.code.extractor :as target]))

;; Register fukan's project FACT extractor — its own Clojure source, as the engine-agnostic
;; `{:roots :var-usages}` facts the native Cozo build consumes.
(extraction/register-fact-extractor! (fn [root] (target/extract-roots [root])))  ; target → canvas.vocab.code.extractor

(defonce ^:private state (atom {:cozo nil :src nil}))

(defn- hold!
  "Hold the model — the `cozo` db consumers read — closing the prior Cozo db first."
  [cozo src]
  (when-let [old (:cozo @state)] (cozo-db/close old))
  (reset! state {:cozo cozo :src src}))

(defn ^{:malli/schema [:=> [:cat :Path] :StructureDb]} load-model
  "Build (or reload) the model for `src` — the native Cozo build (canvas design + the code
   extracted from `src`, on one graph). `build-model` requires every canvas namespace first, so
   the instance-var scan + the extraction facts are available."
  [src]
  (let [model (pipeline/build-model src)]
    (hold! model src)
    (println "Loaded model:"
             (count (cq/q '[:find ?e :where [?e :structure/of _]] model)) "structures,"
             (count (cq/q '[:find ?r :where [?r :rel/kind _]] model)) "relations")
    model))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} get-model
  "The current model (Cozo structure substrate), or nil if not loaded."
  []
  (:cozo @state))

(defn get-src [] (:src @state))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} refresh-model
  "Rebuild the model from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))
