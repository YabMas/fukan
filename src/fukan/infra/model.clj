(ns fukan.infra.model
  "Model lifecycle: hold the structure substrate db (the model — design decision
   (ii)), offering load / refresh / get. `load-model` invokes the build pipeline,
   which ingests the defstructure canvas specs into one structure db.

   DURING THE datascript→Cozo CUT-OVER the model is held DUALLY: the datascript db
   (the consumers not yet ported still read it) AND a Cozo mirror of it (`get-cozo`,
   the consumers already on the Cozo law engine / readers read that). The held Cozo db
   is closed on each reload. Once the last datascript consumer is ported, the ds build
   is dropped and the held db becomes the native Cozo build.

   This is also fukan-on-itself's composition root: it registers fukan's custom code
   extractor (the Clojure extractor over fukan's `src/`) at the `fukan.model.extraction`
   plug-point. The type dialect needs no wiring here — `canvas.vocab.type` self-registers
   the full malli dialect when it loads (required below to guarantee it is)."
  (:require [datascript.core :as d]
            [canvas.vocab.type]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as cozo-db]
            [fukan.cozo.mirror :as cozo-mirror]
            ;; loaded for its side-effect: registers the Cozo backend at structure's
            ;; check-engine plug-point, so `(structure/check cozo-db)` runs the Cozo law engine
            [fukan.cozo.law]
            [canvas.vocab.code.extractor :as target]))

;; Register fukan's project extractor — its own Clojure source.
(extraction/register-extractor! target/extract)  ; target alias → canvas.vocab.code.extractor

(defonce ^:private state (atom {:model nil :cozo nil :src nil}))

(defn- hold!
  "Set the held model to `db` (+ its Cozo mirror), closing the prior Cozo db first."
  [db src]
  (when-let [old (:cozo @state)] (cozo-db/close old))
  (reset! state {:model db :cozo (cozo-mirror/mirror db) :src src}))

(defn ^{:malli/schema [:=> [:cat :Path] :StructureDb]} load-model
  "Build (or reload) the model — the merged structure substrate db — for `src`."
  [src]
  (let [db (pipeline/build-model src)]
    (hold! db src)
    (println "Loaded model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] db)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] db)) "relations")
    db))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} get-model
  "The current model (structure substrate db), or nil if not loaded."
  []
  (:model @state))

(defn get-cozo
  "The current model's Cozo mirror (a Cozo db handle), or nil if not loaded — what the
   ported consumers read while datascript still backs the rest."
  []
  (:cozo @state))

(defn get-src [] (:src @state))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} refresh-model
  "Rebuild the model from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))

(defn ^:test-support set-model-for-test!
  "Test helper — directly set the held model (+ its Cozo mirror). Never call from production code."
  [m]
  (hold! m "test"))
