(ns fukan.infra.model
  "Model lifecycle: hold the structure substrate db (the model — design decision
   (ii)), offering load / refresh / get. `load-model` invokes the build pipeline,
   which ingests the defstructure canvas specs into one structure db.

   DURING THE datascript→Cozo CUT-OVER the model is held DUALLY: the datascript db
   (`get-model` — the oracle + the remaining ds-based tests) AND a Cozo db (`get-cozo`,
   what every ported consumer reads). The held Cozo db is closed on each reload. The Cozo
   side is now the NATIVE build (`cozo-build/model->cozo` — no datascript intermediate);
   the ds build is held alongside only as the verification oracle until it is dropped.

   This is also fukan-on-itself's composition root: it registers fukan's custom code
   extractor (the Clojure extractor over fukan's `src/`) at the `fukan.model.extraction`
   plug-point. The type dialect needs no wiring here — `canvas.vocab.type` self-registers
   the full malli dialect when it loads (required below to guarantee it is)."
  (:require [datascript.core :as d]
            [clojure.java.io :as io]
            [canvas.vocab.type]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.cozo.db :as cozo-db]
            [fukan.cozo.mirror :as cozo-mirror]
            [fukan.cozo.build :as cozo-build]
            ;; loaded for its side-effect: registers the Cozo backend at structure's
            ;; check-engine plug-point, so `(structure/check cozo-db)` runs the Cozo law engine
            [fukan.cozo.law]
            [canvas.vocab.code.extractor :as target]))

;; Register fukan's project extractor — its own Clojure source. Both the datascript `extract`
;; (the oracle) and the engine-agnostic `extract-roots` FACTS (what the native Cozo build consumes).
(extraction/register-extractor! target/extract)               ; target alias → canvas.vocab.code.extractor
(extraction/register-fact-extractor! (fn [root] (target/extract-roots [root])))

(defonce ^:private state (atom {:model nil :cozo nil :src nil}))

(defn- hold!
  "Hold the model: the datascript db `ds` (oracle) + the `cozo` db (what consumers read),
   closing the prior Cozo db first."
  [ds cozo src]
  (when-let [old (:cozo @state)] (cozo-db/close old))
  (reset! state {:model ds :cozo cozo :src src}))

(defn ^{:malli/schema [:=> [:cat :Path] :StructureDb]} load-model
  "Build (or reload) the model for `src`: the datascript build (oracle) + the NATIVE Cozo build
   (`model->cozo`, no datascript) that consumers read. (build-model loads/discovers every canvas
   namespace first, so model->cozo's instance-var scan + the extraction facts are available.)"
  [src]
  (let [ds    (pipeline/build-model src)
        facts (if (and src (.exists (io/file src)))
                (target/extract-roots [src])
                {:roots [] :var-usages []})
        cozo  (cozo-build/model->cozo (canvas-source/canvas-namespaces) facts)]
    (hold! ds cozo src)
    (println "Loaded model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] ds)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] ds)) "relations")
    ds))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} get-model
  "The current model (structure substrate db), or nil if not loaded."
  []
  (:model @state))

(defn get-cozo
  "The current model's Cozo db handle (the native build), or nil if not loaded — what the
   ported consumers read."
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
  "Test helper — directly set the held model (a datascript db) + its Cozo MIRROR. Never call from
   production code. (Tests pass an arbitrary ds db, so this mirrors rather than native-builds.)"
  [m]
  (hold! m (cozo-mirror/mirror m) "test"))
