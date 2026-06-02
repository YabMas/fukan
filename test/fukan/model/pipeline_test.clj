(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            ;; the kernel self-model's schema vocab (MetaSlot authored inline → not referred)
            [canvas.vocab.meta :refer [Concept]]
            [canvas.vocab.arch :refer [Faculty]]))

(defn- names-of [db tag]
  (set (map first (d/q '[:find ?n :in $ ?t
                         :where [?e :structure/of ?t] [?e :entity/name ?n]]
                       db tag))))

(deftest build-model-ingests-canvas-specs-into-a-structure-db
  (testing "the model is the merged structure db over the canvas/ defstructure specs"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "infra.model")
          "the canvas/infra/model spec is discovered and ingested")
      ;; infra is now modelled with the fukan-on-fukan grammar (Stage/Kind), not
      ;; the (evicted) base Function/Type vocab; subset since other specs add more
      (is (set/subset? #{"load-model" "get-model" "refresh-model"} (names-of db :Stage)))
      (is (set/subset? #{"Model" "Src"} (names-of db :Kind)))
      (is (empty? (s/check db))
          "the whole self-model satisfies every structure's laws"))))

(deftest pipeline-self-spec-shares-value-identified-shapes
  (testing "the StructureDb type-shape recurs across stage signatures but is ONE node"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "model.pipeline")
          "the canvas/pipeline/model spec is ingested")
      (is (set/subset? #{"build-model" "ingest" "merge-dbs"} (names-of db :Stage)))
      ;; the StructureDb type-shape appears in 4 positions (merge-dbs in+out,
      ;; ingest out, build-model out) — value-identity collapses it to one node.
      ;; robust to other specs contributing shapes: assert the specific shared one
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :Shape] [?s :val/kind "type"]
                                    [?r :rel/from ?s] [?r :rel/kind :type]
                                    [?r :rel/to ?t] [?t :entity/name "StructureDb"]
                                    [?m :entity/name "model.pipeline"] [?m :module/child ?t]]
                           db)))
          "the StructureDb type-shape — used in four positions — is one value node"))))

(deftest canvas-source-model-shares-the-db-shape
  (testing "in the canvas_source self-model, the Db type-shape (4 uses) is one node"
    (let [db (pipeline/build-model "src")]
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :Shape] [?s :val/kind "type"]
                                    [?r :rel/from ?s] [?r :rel/kind :type]
                                    [?r :rel/to ?t] [?t :entity/name "Db"]
                                    [?m :entity/name "canvas-source"] [?m :module/child ?t]]
                           db)))
          "Db appears in db->entity-maps in, merge-dbs in (nested) + out, build out → one node"))))

(deftest canvas-source-effects-are-captured-and-value-identified
  (testing "Stage effects are recorded; :io (performed by 4 stages) is one shared node"
    (let [db (pipeline/build-model "src")]
      (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :Effect] [?e :val/name "io"]] db)))
          "the :io effect is value-identified across every stage that performs it")
      (is (seq (d/q '[:find ?r
                      :where [?b :structure/of :Stage] [?b :entity/name "build"]
                             [?r :rel/from ?b] [?r :rel/kind :performs] [?r :rel/to ?e]
                             [?e :structure/of :Effect] [?e :val/name "io"]]
                    db))
          "build performs :io"))))

(deftest kernel-meta-model-captures-structure-composition
  (testing "the reflexive kernel model: Structure is composed of Slot and Law"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "core.structure"))
      (is (= #{"slot" "law" "value"}
             (set (map first (d/q '[:find ?n
                                    :where [?st :structure/of :Concept] [?st :entity/name "Structure"]
                                           [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                                           [?ms :val/name ?n]] db))))
          "Structure's MetaSlots are slot, law, value")
      (is (seq (d/q '[:find ?slot-c
                      :where [?st :structure/of :Concept] [?st :entity/name "Structure"]
                             [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                             [?ms :val/name "slot"]
                             [?o :rel/from ?ms] [?o :rel/kind :of] [?o :rel/to ?slot-c]
                             [?slot-c :entity/name "Slot"]] db))
          "Structure.slot targets the Slot concept (composition)"))))

(deftest metaslot-cardinality-law-catches-unknown-cardinality
  (testing "a MetaSlot whose cardinality is outside the known set is caught"
    (let [bad (s/with-structures
                (s/within-module "k"
                  (Concept "T")
                  (Concept "X" (slot (MetaSlot (name "f") (cardinality "lots") (of T))))))]
      (is (contains? (set (map :law (s/check bad)))
                     "a slot's cardinality is one of the known cardinalities")))))

(deftest overview-model-makes-the-model-the-hub
  (testing "the top-level overview: every faculty connects to the Model, which is the hub"
    (let [db (pipeline/build-model "src")]
      (is (contains? (names-of db :Module) "fukan"))
      ;; the Model faculty has the most flow connections (everything orbits it)
      (is (<= 6 (count (d/q '[:find ?r
                              :where [?m :structure/of :Faculty] [?m :entity/name "Model"]
                                     (or [?r :rel/to ?m] [?r :rel/from ?m])]
                            db)))
          "many faculties feed or read the Model"))))

(deftest orphan-faculty-is-caught
  (testing "a faculty with no flow edges trips the no-isolated-faculty law"
    (let [db (s/with-structures
               (s/within-module "f"
                 (Faculty "Model")
                 (Faculty "Reader" (reads Model))   ; Model gets an incoming edge; Reader an outgoing
                 (Faculty "Loner")))]               ; connects to nothing
      (is (contains? (set (map :law (s/check db))) "no faculty is isolated")))))

(deftest cross-module-ref-resolves-post-merge
  (testing "overview's Structure faculty is realized-by → the core.structure module node"
    (let [db (pipeline/build-model "src")]
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Structure"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "core.structure"]]
                    db))
          "the cross-ref resolved to the core.structure :Module node")
      (is (empty? (d/q '[:find ?r :where [?r :rel/to-ref _] (not [?r :rel/to _])] db))
          "every cross-ref in the merged model is resolved"))))

(deftest unresolved-cross-ref-throws
  (testing "a cross-ref to a non-existent module/node throws at resolution"
    (let [db (d/db-with (s/create) [{:entity/id "r1" :rel/to-ref ["nope"]}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unresolved cross-module reference"
                            (canvas-source/resolve-cross-refs db))))))
