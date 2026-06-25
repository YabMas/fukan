(ns canvas.vocab.code.extractor-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so (s/check db) dispatches to it
            [fukan.cozo.law]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [canvas.vocab.code.extractor :as tc]
            [canvas.vocab.code.operation :as corr]))

;; Register fukan's FACT extractor so build-model's unified build runs it (the proof).
(extraction/register-fact-extractor! (fn [root] (tc/extract-roots [root])))

(defn- extract
  "The extraction FACTS for `path` assembled into a code-only Cozo substrate (no canvas nss)
   with the :calls graph grounded + grammar reflected."
  [path]
  (build/model->cozo [] (tc/extract-roots [path])))

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Operation per defn/defn-, with privacy"
    (let [db  (extract "test/fixtures/target/sample.clj")
          ops (into {} (cq/q '[:find ?n ?p
                              :where [?e :structure/of :canvas.vocab.code.operation/Operation]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Operation; the def (gamma) is ignored; defn- is private"))))

(deftest lifts-malli-schema-into-sig
  (testing "an annotated defn's :malli/schema metadata is stamped as the Operation's :val/sig"
    (let [db  (extract "test/fixtures/target/sample.clj")
          sig (ffirst (cq/q '[:find ?s
                             :where [?e :structure/of :canvas.vocab.code.operation/Operation] [?e :entity/name "alpha"]
                                    [?e :val/sig ?s]]
                           db))]
      (is (= "[:=> [:cat :int] :int]" sig)
          "alpha's malli/schema metadata is lifted, pr-str'd, onto :val/sig"))))

(deftest operations-are-owned-by-their-subsystem
  (testing "each namespace becomes a Module that owns its Operations (via :child relations)"
    (let [db    (extract "test/fixtures/target/sample.clj")
          owned (cq/q '[:find ?mn ?on
                       :where [?m :structure/of :canvas.vocab.code.module/Module] [?m :entity/name ?mn]
                              [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?o]
                              [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is a Module owning all three operations"))))

(deftest emits-calls-between-operations
  (testing "the extractor populates :calls from clj-kondo var-usages — beta calls alpha"
    (let [db    (extract "test/fixtures/target/sample.clj")
          calls (cq/q '[:find ?fromn ?ton
                       :where [?cr :rel/kind :calls] [?cr :rel/from ?f] [?cr :rel/to ?t]
                              [?f :entity/name ?fromn] [?t :entity/name ?ton]]
                     db)]
      (is (contains? (set calls) ["beta" "alpha"])
          "beta -> alpha is emitted as a :calls relation")
      (is (not (some (fn [[a b]] (= a b)) calls)) "no self-call edges"))))

(deftest extracted-modules-carry-provenance
  (testing "each extracted Module is stamped :val/extracted true"
    (let [db (extract "test/fixtures/target/sample.clj")]
      (is (true? (ffirst (cq/q '[:find ?x :where [?m :structure/of :canvas.vocab.code.module/Module]
                                              [?m :entity/name "sample"] [?m :val/extracted ?x]] db)))
          "the sample Module is provenance-stamped"))))

(deftest extracts-defmulti-as-dispatch-operation
  (testing "a defmulti is extracted as an Operation (a dispatch point) and callers' calls to it resolve"
    (let [db (extract "src/fukan/canvas/projection/probes.clj")]
      (is (true? (ffirst (cq/q '[:find ?x :where [?o :structure/of :canvas.vocab.code.operation/Operation]
                                              [?o :entity/name "run-probe"] [?o :val/extracted ?x]] db)))
          "run-probe (a defmulti) is an extracted Operation")
      (is (contains? (set (cq/q '[:find ?fromn ?ton
                                 :where [?c :rel/kind :calls] [?c :rel/from ?f] [?c :rel/to ?t]
                                        [?f :entity/name ?fromn] [?t :entity/name ?ton]] db))
                     ["run" "run-probe"])
          "run -> run-probe resolves as a :calls edge now that the point is a node"))))

(deftest every-modelled-stage-is-realized-in-src
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/)
            with the code extracted from src/ on one graph, and every modelled
            op-layer Operation is backed by a real function — the cross-layer
            correspondence is assertable only because both layers share that graph"
    (let [model      (pipeline/build-model "src")        ; design + extracted code, unified
          unrealized (corr/drifted-operations model)]
      ;; sanity: build-model actually brought both layers together
      (is (seq (cq/q '[:find ?s :where [?s :structure/of :canvas.vocab.code.operation/Operation]] model)) "model has Operations")
      (is (seq (cq/q '[:find ?o :where [?o :structure/of :canvas.vocab.code.operation/Operation]] model)) "build-model extracted code into Operations")
      (is (empty? unrealized)
          (str "every modelled Operation should map to a same-named extracted function; "
               "unrealized (drift): " unrealized)))))
