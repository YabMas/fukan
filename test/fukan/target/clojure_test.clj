(ns fukan.target.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.model.pipeline :as pipeline]
            [fukan.target.clojure :as tc]))

(deftest extracts-functions-as-operations
  (testing "the clj-kondo extractor emits an Operation per defn/defn-, with privacy"
    (let [db  (tc/extract "test/fixtures/target/sample.clj")
          ops (into {} (d/q '[:find ?n ?p
                              :where [?e :structure/of :Operation]
                                     [?e :entity/name ?n] [?e :val/private ?p]]
                            db))]
      (is (= {"alpha" false "beta" false "delta" true} ops)
          "every defn/defn- becomes an Operation; the def (gamma) is ignored; defn- is private"))))

(deftest operations-are-owned-by-their-module
  (testing "each namespace becomes a Module that owns its Operations (:module/child)"
    (let [db    (tc/extract "test/fixtures/target/sample.clj")
          owned (d/q '[:find ?mn ?on
                       :where [?m :structure/of :Module] [?m :entity/name ?mn]
                              [?m :module/child ?o] [?o :structure/of :Operation]
                              [?o :entity/name ?on]]
                     db)]
      (is (= #{["sample" "alpha"] ["sample" "beta"] ["sample" "delta"]} (set owned))
          "the `sample` namespace is a Module owning all three operations"))))

(deftest every-modelled-stage-is-realized-in-src
  (testing "fukan-on-itself: extract src/fukan, merge fukan's authored self-model,
            and verify every modelled op-layer Stage is backed by a real function —
            the cross-layer correspondence is assertable only because the authored
            Stages (canvas/) and the extracted Operations (src/) share one graph"
    (let [model     (pipeline/build-model "src")
          extracted (tc/extract "src/fukan")
          merged    (cs/resolve-cross-refs (cs/merge-dbs [model extracted]))
          law       "every modelled Stage is realized by an Operation of the same name"
          unrealized (->> (s/check merged)
                          (filter #(= law (:law %)))
                          (mapcat :offenders) (map first)
                          (map #(:entity/name (d/entity merged %)))
                          set)]
      ;; sanity: the merge actually brought both layers together
      (is (seq (d/q '[:find ?s :where [?s :structure/of :Stage]] merged)) "model has Stages")
      (is (seq (d/q '[:find ?o :where [?o :structure/of :Operation]] merged)) "src extracted to Operations")
      (is (empty? unrealized)
          (str "every modelled Stage should map to a same-named extracted function; "
               "unrealized (drift): " unrealized)))))
