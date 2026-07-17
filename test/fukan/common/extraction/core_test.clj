(ns fukan.common.extraction.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]                 ; check + its worklist readers (violation-names) live here
            [fukan.model.extraction :as extraction]
            [fukan.model.pipeline :as pipeline]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.clojure.effect :as clj-effect]))

;; Register fukan's FACT extractor so build-model's unified build runs it (the proof).
(extraction/register-fact-extractor! (fn [root] (tc/extract-roots [root])))

(defn- extract
  "The extraction FACTS for `path` assembled into a code-only Cozo substrate (no canvas nss)
   with the :calls graph grounded + grammar reflected."
  [path]
  (build/model->cozo [] (tc/extract-roots [path])))

(deftest clojure-effect-classification-is-owned-by-the-clojure-extractor
  (testing "Clojure var-usages classify direct effects outside the generic Effect vocab"
    (is (= {["demo" "load"] #{:io}
            ["demo" "boom"] #{:throws}}
           (clj-effect/op-effects [{:from 'demo :from-var 'load :to 'clojure.core :name 'slurp}
                                   {:from 'demo :from-var 'boom :to 'clojure.core :name 'throw}
                                   {:from 'demo :from-var 'log :to 'clojure.core :name 'println}])))))

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Fn (the Operation codomain) per defn/defn-, with privacy"
    (let [db  (extract "test/fixtures/target/sample.clj")
          ops (into {} (cq/q '[:find ?n ?p
                              :where [?e :structure/of :fukan.common.extraction.clojure.operation/Fn]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Fn; the def (gamma) is ignored; defn- is private"))))

(deftest decomposes-malli-schema-into-in-out
  (testing "an annotated defn's :malli/schema is DECOMPOSED into :in/:out Schema nodes (no blob)"
    (let [db  (extract "test/fixtures/target/sample.clj")
          out (ffirst (cq/q '[:find ?k
                             :where [?e :structure/of :fukan.common.extraction.clojure.operation/Fn] [?e :entity/name "alpha"]
                                    [?r :rel/from ?e] [?r :rel/kind :out] [?r :rel/to ?s] [?s :val/kind ?k]]
                           db))
          ins (cq/q '[:find [?k ...]
                     :where [?e :structure/of :fukan.common.extraction.clojure.operation/Fn] [?e :entity/name "alpha"]
                            [?r :rel/from ?e] [?r :rel/kind :in] [?r :rel/to ?s] [?s :val/kind ?k]]
                   db)]
      (is (= "int" out) "alpha's [:=> [:cat :int] :int] :out is a Schema of kind int")
      (is (= ["int"] ins) "alpha's :in is [int]"))))

(deftest operations-are-owned-by-their-subsystem
  (testing "each namespace becomes an Ns (the Module codomain) that owns its Fns (via :child relations)"
    (let [db    (extract "test/fixtures/target/sample.clj")
          owned (cq/q '[:find ?mn ?on
                       :where [?m :structure/of :fukan.common.extraction.clojure.module/Ns] [?m :entity/name ?mn]
                              [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?o]
                              [?o :structure/of :fukan.common.extraction.clojure.operation/Fn] [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is an Ns owning all three functions"))))

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
  (testing "each extracted Ns is stamped :val/extracted true"
    (let [db (extract "test/fixtures/target/sample.clj")]
      (is (true? (ffirst (cq/q '[:find ?x :where [?m :structure/of :fukan.common.extraction.clojure.module/Ns]
                                              [?m :entity/name "sample"] [?m :val/extracted ?x]] db)))
          "the sample Ns is provenance-stamped"))))

(deftest extracts-defmulti-as-operation
  (testing "a defmulti is extracted as a (polymorphic) Fn, its callers resolve as :calls, and a call inside a defmethod body re-homes onto the multimethod"
    (let [db    (extract "test/fixtures/target/poly.clj")
          calls (set (cq/q '[:find ?fromn ?ton
                             :where [?c :rel/kind :calls] [?c :rel/from ?f] [?c :rel/to ?t]
                                    [?f :entity/name ?fromn] [?t :entity/name ?ton]] db))]
      (is (true? (ffirst (cq/q '[:find ?x :where [?o :structure/of :fukan.common.extraction.clojure.operation/Fn]
                                              [?o :entity/name "render-shape"] [?o :val/extracted ?x]] db)))
          "render-shape (a defmulti) is an extracted Fn")
      (is (contains? calls ["describe" "render-shape"])
          "describe -> render-shape resolves as a :calls edge (a defmulti is an ordinary op)")
      (is (contains? calls ["render-shape" "area"])
          "a call inside a defmethod body (area) re-homes onto the multimethod render-shape (attribute-defmethod-bodies)"))))

(deftest every-modelled-stage-is-realized-in-src
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/)
            with the code extracted from src/ on one graph, and every modelled
            op-layer Operation is backed by a real function — the cross-layer
            correspondence is assertable only because both layers share that graph"
    (let [model      (pipeline/build-model "src")        ; design + extracted code, unified
          unrealized (law/violation-names model :corresponds/Operation.realized)]
      ;; sanity: build-model actually brought both layers together
      (is (seq (cq/q '[:find ?s :where [?s :structure/of :fukan.common.vocab.code.operation/Operation]] model)) "model has design Operations")
      (is (seq (cq/q '[:find ?o :where [?o :structure/of :fukan.common.extraction.clojure.operation/Fn]] model)) "build-model extracted code into Fns")
      (is (empty? unrealized)
          (str "every modelled Operation should map to a same-named extracted function; "
               "unrealized (drift): " unrealized)))))
