(ns fukan.cozo.predicate-port-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]))

(deftest name-match-is-a-generic-kernel-strategy-not-a-domain-predicate
  (testing "a correspondence bridge lowers through the kernel's generic `name-match` builtin — the
            compiler names no code-vocab; a known strategy compiles to the match filter, an unknown
            one fails loudly at compile"
    (let [db (build/maps->cozo [{:entity/id "cm"  :structure/of :x/T :entity/name "infra-model"}
                                {:entity/id "km"  :structure/of :x/T :entity/name "fukan.infra.model" :val/extracted true}
                                {:entity/id "km2" :structure/of :x/T :entity/name "fukan.other.thing" :val/extracted true}]
                               [])]
      ;; :qualified-suffix pairs a design name with a fact name it is a separator-agnostic dotted
      ;; suffix of — infra-model ← fukan.infra.model, but NOT ← fukan.other.thing
      (is (= #{["infra-model" "fukan.infra.model"]}
             (set (cq/q '[:find ?cn ?kn :in $ %
                          :where (pair ?cn ?kn)]
                        db
                        '[[(pair ?cn ?kn)
                           [?c :structure/of :x/T] (not [?c :val/extracted true]) [?c :entity/name ?cn]
                           [?k :structure/of :x/T] [?k :val/extracted true] [?k :entity/name ?kn]
                           [(name-match :qualified-suffix ?cn ?kn)]]])))
          "the :qualified-suffix strategy compiles to the dotted-suffix match")
      ;; an unknown strategy is not silently dropped — it explodes at compile
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-match strategy"
                            (cq/q '[:find ?cn :in $ % :where (bad ?cn ?kn)]
                                  db
                                  '[[(bad ?cn ?kn)
                                     [?c :entity/name ?cn] [?k :entity/name ?kn]
                                     [(name-match :no-such-strategy ?cn ?kn)]]])))
      ;; the de-leak: the generic compiler SOURCE names no code-vocab
      (let [src (slurp "src/fukan/cozo/query.clj")]
        (is (not (re-find #"module-corresponds|code\.module|Module" src))
            "no code-vocab predicate/tag hardcoded in the compiler")))))

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
