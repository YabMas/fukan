(ns fukan.cozo.law-test
  "The Cozo law engine (datalog→CozoScript compiler) — first family: slot-cardinality
   laws (datom / not / not-join / not=). Verifies the compiled CozoScript and that the
   compiled laws agree with datascript's `check` on the real model (no false positives)."
  (:require [clojure.test :refer [deftest is testing]]
            ;; composition root — registers the extractor for build-model "src"
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as structure]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.law :as law]))

(deftest compiles-a-not-join-cardinality-law
  (testing "compile-law emits the expected CozoScript for a none (not-join) law"
    (is (= (str "nj_1[x] := triple[r, 'rel/from', x], triple[r, 'rel/kind', 'exposes']\n"
                "?[x] := triple[x, 'structure/of', 'canvas.vocab.code.module/Module'], not nj_1[x]")
           (law/compile-law '{:offenders [?x]
                              :where [[?x :structure/of :canvas.vocab.code.module/Module]
                                      (not-join [?x] [?r :rel/from ?x] [?r :rel/kind :exposes])]}
                            #{})))))

(deftest compiles-a-not=-cardinality-law
  (testing "compile-law emits the expected CozoScript for an at-most-one (not=) law"
    (is (= (str "?[x] := triple[x, 'structure/of', 'M/K'], triple[r1, 'rel/from', x], "
                "triple[r1, 'rel/kind', 'shape'], triple[r2, 'rel/from', x], "
                "triple[r2, 'rel/kind', 'shape'], r1 != r2")
           (law/compile-law '{:offenders [?x]
                              :where [[?x :structure/of :M/K]
                                      [?r1 :rel/from ?x] [?r1 :rel/kind :shape]
                                      [?r2 :rel/from ?x] [?r2 :rel/kind :shape]
                                      [(not= ?r1 ?r2)]]}
                            #{})))))

(deftest compiled-laws-agree-with-datascript-on-the-real-model
  (testing "the compiler supports a family of laws, and they find no offenders on the green model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (let [results  (law/check-structural cdb)
              compiled (remove :unsupported results)
              fired    (filter :offenders compiled)]
          (is (seq compiled) "precondition: the compiler supports the slot-cardinality law family")
          (is (empty? fired) "no compiled law false-positives on the green real model")
          (is (zero? (count (structure/check ds))) "and datascript's check agrees the model is clean"))
        (finally (db/close cdb))))))
