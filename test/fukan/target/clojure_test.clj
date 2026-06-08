(ns fukan.target.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.target.clojure :as tc]
            [fukan.target.correspondence :as corr]))

;; Register fukan's extractor so build-model's unified build runs it (the proof).
(extraction/register-extractor! tc/extract)

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Operation per defn/defn-, with privacy"
    (let [db  (tc/extract "test/fixtures/target/sample.clj")
          ops (into {} (d/q '[:find ?n ?p
                              :where [?e :structure/of :Operation]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Operation; the def (gamma) is ignored; defn- is private"))))

(deftest lifts-malli-schema-into-sig
  (testing "an annotated defn's :malli/schema metadata is stamped as the Operation's :val/sig"
    (let [db  (tc/extract "test/fixtures/target/sample.clj")
          sig (ffirst (d/q '[:find ?s
                             :where [?e :structure/of :Operation] [?e :entity/name "alpha"]
                                    [?e :val/sig ?s]]
                           db))]
      (is (= "[:=> [:cat :int] :int]" sig)
          "alpha's malli/schema metadata is lifted, pr-str'd, onto :val/sig"))))

(deftest operations-are-owned-by-their-subsystem
  (testing "each namespace becomes a Subsystem that owns its Operations (via :child relations)"
    (let [db    (tc/extract "test/fixtures/target/sample.clj")
          owned (d/q '[:find ?mn ?on
                       :where [?m :structure/of :Subsystem] [?m :entity/name ?mn]
                              [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?o]
                              [?o :structure/of :Operation] [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is a Subsystem owning all three operations"))))

(deftest every-modelled-stage-is-realized-in-src
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/)
            with the code extracted from src/ on one graph, and every modelled
            op-layer Operation is backed by a real function — the cross-layer
            correspondence is assertable only because both layers share that graph"
    (let [model      (pipeline/build-model "src")        ; design + extracted code, unified
          unrealized (corr/drifted-operations model)]
      ;; sanity: build-model actually brought both layers together
      (is (seq (d/q '[:find ?s :where [?s :structure/of :Operation]] model)) "model has Operations")
      (is (seq (d/q '[:find ?o :where [?o :structure/of :Operation]] model)) "build-model extracted code into Operations")
      (is (empty? unrealized)
          (str "every modelled Operation should map to a same-named extracted function; "
               "unrealized (drift): " unrealized)))))
