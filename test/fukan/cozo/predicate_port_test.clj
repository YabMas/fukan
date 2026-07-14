(ns fukan.cozo.predicate-port-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            ;; loading the vocab module runs its self-registration of the module-corresponds? port
            [fukan.common.vocab.code.module]))

(deftest module-corresponds-port-is-vocab-registered-not-kernel-hardcoded
  (testing "the module-corresponds? Cozo port lives in vocab, not the generic kernel compiler"
    ;; vocab self-registered the port + its synthetic CozoScript rules (present at runtime)
    (is (contains? (deref @#'cq/predicate-registry) 'fukan.common.vocab.code.module/module-corresponds?)
        "module.clj registered the predicate port")
    (is (contains? (deref @#'cq/synthetic-rules) "r_module_corresponds")
        "and its synthetic CozoScript rules came in with the registration")
    ;; the de-leak: the generic compiler SOURCE names no code-vocab predicate/tag
    (let [src (slurp "src/fukan/cozo/query.clj")]
      (is (not (re-find #"module-corresponds" src)) "no module-corresponds? hardcoded in the compiler")
      (is (not (re-find #"code\.module/Module" src)) "no Module tag named in the compiler"))))

(deftest unported-predicate-in-injected-rule-throws
  (testing "an extra rule calling an unregistered predicate fails LOUDLY at query compile —
            a portless rule silently dropping would be a false-green hazard (e.g. a corresponds
            bridge whose port was never registered would silently lose its twin disjunct)"
    (let [db (build/maps->cozo [{:entity/id "n" :structure/of :x/T :entity/name "n"}] [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported predicate"
                            (cq/q '[:find ?a :in $ %
                                    :where (ghost ?a ?b)]
                                  db
                                  '[[(ghost ?a ?b) [?a :entity/name ?an] [?b :entity/name ?bn]
                                     [(no.such.ns/not-ported ?an ?bn)]]])))
      ;; discriminating variant: query whose :where does NOT call the ghost rule but
      ;; the ruleset still contains it — the whole ruleset is compiled eagerly, so an
      ;; unported predicate in an unused rule still explodes (no silent skip)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported predicate"
                            (cq/q '[:find ?a :in $ %
                                    :where [?a :entity/name "n"]]
                                  db
                                  '[[(ghost ?a ?b) [?a :entity/name ?an] [?b :entity/name ?bn]
                                     [(no.such.ns/not-ported ?an ?bn)]]]))))))

