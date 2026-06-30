(ns fukan.canvas.core.membership-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.rules]
            [fukan.canvas.core.structure :as s]
            [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.vocab.code.subsystem :refer [Subsystem]]))

;; a containment ladder: subsystem m-sub ─child→ module m-mod ─exposes→ op m-op (m-op performs :io)
(declare mc-mod)
(Operation ^{:name "m-op"} mc-op {:performs [:io]})
(Module ^{:name "m-mod"} mc-mod {:exposes [mc-op]})
(Subsystem ^{:name "m-sub"} mc-sub {:child [mc-mod]})

(defn- names [db eids] (set (map #(:entity/name (cq/entity db %)) eids)))

(deftest contains-unions-the-containment-relations-and-rolls-up
  (testing "the :contains character generates `contains` (the child/exposes/owns union) + contains+ (closure)"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])
          q  (fn [rule a] (set (cq/q (vec (concat '[:find [?m ...] :in $ % ?an :where [?a :entity/name ?an]]
                                                  [(list rule '?a '?m)]))
                                     db (s/vocab-rules) a)))]
      ;; direct containment (exposes / child) collapses to `contains`
      (is (= #{"m-op"}  (names db (q 'contains  "m-mod"))) "m-mod contains m-op (via :exposes)")
      (is (= #{"m-mod"} (names db (q 'contains  "m-sub"))) "m-sub contains m-mod (via :child)")
      ;; transitive containment (the rollup) reaches the op from the subsystem
      (is (= #{"m-op" "m-mod"} (names db (q 'contains+ "m-sub"))) "m-sub contains+ reaches both"))))

(deftest via-contains-lands-effectful-at-module-and-subsystem
  (testing "(via :contains Scope effectful) rolls the effectful property up the containment ladder"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])]
      (is (= #{"m-mod"} (names db (lens/focus-nodes db '[(via :contains Module effectful)])))
          "the module that contains a directly-effectful op")
      (is (= #{"m-sub"} (names db (lens/focus-nodes db '[(via :contains Subsystem effectful)])))
          "the subsystem that transitively contains a directly-effectful op"))))

(deftest in-module-is-derived-from-contains-and-substrate-names-no-vocab
  (testing "in-module still resolves (now via contains), and substrate-rules names no vocab relation"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])]
      (is (= #{"m-mod"} (set (cq/q '[:find [?mn ...] :in $ % :where [?o :entity/name "m-op"] (in-module ?o ?mn)]
                                   db (s/vocab-rules))))
          "m-op is in module m-mod (via the derived in-module)"))
    ;; the de-leak itself: substrate-rules must not name child/exposes/owns
    (let [pr (pr-str fukan.canvas.core.rules/substrate-rules)]
      (is (not (or (re-find #":child" pr) (re-find #":exposes" pr) (re-find #":owns" pr)))
          "substrate-rules names no code-vocab relation kind"))))
