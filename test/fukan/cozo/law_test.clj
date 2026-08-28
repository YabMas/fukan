(ns fukan.cozo.law-test
  "The Cozo law engine (datalog→CozoScript compiler) — first family: slot-cardinality
   laws (datom / not / not-join / not=). Unit-tests the compiled CozoScript, and that the
   compiled law family finds no false positives on the real (green) model. (Cozo-standalone —
   the datascript oracle this once carried retired with the dep; the broader cozo law
   coverage now lives in the correspondence / subsystem / laws tests.)"
  (:require [clojure.test :refer [deftest is testing]]
            ;; composition root — registers the fact extractor for build-model "src"
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as db]
            [fukan.cozo.law :as law]))

(deftest compiles-a-not-join-cardinality-law
  (testing "compile-law emits the expected CozoScript for a none (not-join) law"
    ;; the not-join helper is named by content hash (pure compiler) — `nj_hsighp` is the
    ;; deterministic name for this clause, stable across runs
    ;; every clause is DIRECT stored-relation access — never a view (a view is materialized and a
    ;; materialized relation has no key, which cost ~136x on any multi-hop join). The bucket comes
    ;; from the substrate's fixed encoding, so this compiles the same with no db in hand.
    (is (= (str "nj_hsighp[x] := *t_int[r, 'rel/from', x], *t_str[r, 'rel/kind', 'exposes']\n"
                "?[x] := *t_str[x, 'structure/of', 'fukan.common.vocab.code.module/Module'], not nj_hsighp[x]")
           (law/compile-law '{:offenders [?x]
                              :where [[?x :structure/of :fukan.common.vocab.code.module/Module]
                                      (not-join [?x] [?r :rel/from ?x] [?r :rel/kind :exposes])]}
                            #{} {})))))

(deftest compiles-a-not=-cardinality-law
  (testing "compile-law emits the expected CozoScript for an at-most-one (not=) law"
    (is (= (str "?[x] := *t_str[x, 'structure/of', 'M/K'], *t_int[r1, 'rel/from', x], "
                "*t_str[r1, 'rel/kind', 'shape'], *t_int[r2, 'rel/from', x], "
                "*t_str[r2, 'rel/kind', 'shape'], r1 != r2")
           (law/compile-law '{:offenders [?x]
                              :where [[?x :structure/of :M/K]
                                      [?r1 :rel/from ?x] [?r1 :rel/kind :shape]
                                      [?r2 :rel/from ?x] [?r2 :rel/kind :shape]
                                      [(not= ?r1 ?r2)]]}
                            #{} {})))))

(deftest compiled-laws-find-no-false-positives-on-the-real-model
  (testing "the compiler supports a family of laws, and they find no offenders on the green model"
    (let [cdb (pipeline/build-model "src")]
      (try
        (let [results  (law/check-structural cdb)
              compiled (remove :unsupported results)
              fired    (filter :offenders compiled)]
          (is (seq compiled) "precondition: the compiler supports the slot-cardinality law family")
          (is (empty? fired) "no compiled law false-positives on the green real model"))
        (finally (db/close cdb))))))

(deftest check-fails-closed-when-any-law-is-unsupported
  (testing "an unevaluated law can never disappear into a false-green satisfaction result"
    (with-redefs [law/check-structural
                  (fn [_]
                    [{:structure :x/T :law "supported"}
                     {:structure :x/T :law "not compiled" :unsupported true}])]
      (try
        (law/check ::db)
        (is false "check must throw when satisfaction is undecidable")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"cannot decide model satisfaction" (ex-message e)))
          (is (= [{:structure :x/T :law "not compiled" :unsupported true}]
                 (:unsupported (ex-data e)))))))))

(deftest a-violation-carries-the-law-s-offender-var-names
  (testing "a multi-var law's row is four names in a line unless something says which column is
            which. `:vars` travels with the rows so a consumer — a CLI, an agent harness — can
            label them, which is the difference between a finding that is readable downstream
            and one that has to be correlated back against the law by hand."
    (let [cdb (pipeline/build-model "src")]
      (try
        (let [results (remove :unsupported (law/check-structural cdb))]
          (is (seq results) "precondition: some law compiled")
          (is (every? #(vector? (:vars %)) results))
          (is (every? #(every? symbol? (:vars %)) results)
              "the law's own var symbols, not a rendering of them"))
        (finally (db/close cdb))))))

(deftest a-hyphenated-offender-var-still-compiles
  (testing "a datalog var is an ordinary Clojure symbol, and `?from-band` is how one is
            naturally spelled. Unmunged it lowers to the invalid CozoScript identifier
            `from-band` and the law fails closed as UNDECIDABLE — which is honest, and still
            the wrong answer. Offender var names travel to consumers now, so they are chosen
            to read."
    (is (= (str "?[from_band, to_band] := *t_str[from_band, 'structure/of', 'M/K'], "
                "*t_int[r, 'rel/from', from_band], *t_int[r, 'rel/to', to_band]")
           (law/compile-law '{:offenders [?from-band ?to-band]
                              :where [[?from-band :structure/of :M/K]
                                      [?r :rel/from ?from-band]
                                      [?r :rel/to ?to-band]]}
                            #{} {})))))
