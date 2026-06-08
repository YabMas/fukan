(ns fukan.infra.model
  "Model lifecycle: hold the structure substrate db (the model — design decision
   (ii)), offering load / refresh / get. `load-model` invokes the build pipeline,
   which ingests the defstructure canvas specs into one structure db.

   This is also fukan-on-itself's composition root for its dialects: it registers
   fukan's custom code extractor (the Clojure extractor over fukan's `src/`) at the
   `fukan.model.extraction` plug-point and fukan's type dialect (malli) at the
   `fukan.model.typing` plug-point, so the (generic) pipeline can use them without
   naming them."
  (:require [datascript.core :as d]
            [fukan.dialect.malli :as malli]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.model.typing :as typing]
            [fukan.target.clojure :as target]))

;; Register fukan's project extractor — its own Clojure source.
(extraction/register-extractor! target/extract)
;; Register fukan's project type dialect — malli.
(typing/register-type-dialect! {:render malli/render :adheres? malli/sigs-adhere?})

(defonce ^:private state (atom {:model nil :src nil}))

(defn ^{:malli/schema [:=> [:cat :Path] :StructureDb]} load-model
  "Build (or reload) the model — the merged structure substrate db — for `src`."
  [src]
  (let [db (pipeline/build-model src)]
    (reset! state {:model db :src src})
    (println "Loaded model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] db)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] db)) "relations")
    db))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} get-model
  "The current model (structure substrate db), or nil if not loaded."
  []
  (:model @state))

(defn get-src [] (:src @state))

(defn ^{:malli/schema [:=> [:cat] :StructureDb]} refresh-model
  "Rebuild the model from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))

(defn set-model-for-test!
  "Test helper — directly set the held model. Never call from production code."
  [m]
  (reset! state {:model m :src "test"}))
