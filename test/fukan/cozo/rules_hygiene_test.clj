(ns fukan.cozo.rules-hygiene-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.pipeline :as p]
            ;; composition root — registers the extractor + check engine (so build-model "src" extracts)
            [fukan.infra.model]
            [canvas.vocab.code.subsystem :as subsystem]))

(deftest cozo-rule-substrate-names-no-code-surface-vocab
  (testing "the surface code-surface rules moved to vocab; cozo/rules.clj names no Operation/:calls"
    (let [src (slurp "src/fukan/cozo/rules.clj")]
      (is (not (re-find #"code\.operation/Operation" src)) "no Operation tag in the cozo rule substrate")
      (is (not (re-find #"'calls'" src))                    "no :calls relation named in the cozo rule substrate"))))

(deftest latent-boundaries-still-runs-via-the-relocated-surface
  (testing "latent-boundaries composes the now-vocab-local surface rules + ConnectedComponents without error"
    (is (map? (subsystem/latent-boundaries (p/build-model "src")))
        "returns a map — the surface/ConnectedComponents path compiles and runs post-relocation")))
