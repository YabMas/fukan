(ns fukan.cozo.reading-test
  "The first P2 oracle: a reader ported onto Cozo must agree with its datascript
   twin. module-dependencies (calls ∪ data-adoption) over the real held model."
  (:require [clojure.test :refer [deftest is testing]]
            ;; composition root — registers the extractor so build-model "src"
            ;; merges the extracted code graph onto the design graph
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.reading :as reading]
            [canvas.vocab.code.module :as code-module]))

(deftest module-dependencies-matches-datascript
  (testing "the Cozo port agrees with the datascript reader on the real model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (let [expected (code-module/module-dependencies ds)
              actual   (reading/module-dependencies cdb)]
          (is (seq expected) "precondition: the real model has module dependencies")
          (is (= expected actual)
              "cozo == datascript for the full module-dependency graph"))
        (finally (db/close cdb))))))
