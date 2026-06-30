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
