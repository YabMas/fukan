(ns fukan.cozo.build-hygiene-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.model.pipeline :as p]
            ;; composition root — registers the extractor (so build-model "src" extracts + grounds)
            [fukan.infra.model]))

(deftest call-graph-grounded-via-the-extraction-ground-hook
  (testing "the :calls graph is grounded by the extractor's :ground hook, not by cozo/build"
    (let [db (p/build-model "src")]
      (is (pos? (count (cq/q '[:find ?r :where [?r :rel/kind :calls]] db)))
          ":calls rels are grounded (the extractor's :ground hook ran post-build)"))
    (let [src (slurp "src/fukan/cozo/build.clj")]
      (is (not (re-find #"code\.operation/Operation" src)) "cozo/build.clj names no Operation tag")
      (is (not (re-find #"'calls'" src))                    "cozo/build.clj names no :calls relation"))))

(deftest cozo-eav-substrate-names-no-membership-vocab
  (testing "in_module moved to vocab; cozo/rules.clj's generic substrate names no child/exposes/owns"
    (let [src (slurp "src/fukan/cozo/rules.clj")]
      (is (not (re-find #"'child'|'exposes'|'owns'" src))
          "no code-vocab membership relation kinds in the generic cozo substrate")))
  (testing "membership still resolves from vocab (latent-boundaries + :calls grounding both depend on it)"
    (let [db (p/build-model "src")]
      (is (map? ((requiring-resolve 'canvas.principles.layered-architecture/latent-boundaries) db))
          "latent-boundaries composes in_module from vocab without error")
      (is (pos? (count (cq/q '[:find ?r :where [?r :rel/kind :calls]] db)))
          ":calls grounding (which resolves ops by in_module) still works"))))
