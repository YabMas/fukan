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

(deftest member-unions-the-membership-relations-and-rolls-up
  (testing "the :member character generates `member` (the child/exposes/owns union) + member+ (closure)"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])
          q  (fn [rule a] (set (cq/q (vec (concat '[:find [?m ...] :in $ % ?an :where [?a :entity/name ?an]]
                                                  [(list rule '?a '?m)]))
                                     db (s/vocab-rules) a)))]
      ;; direct membership (exposes / child) collapses to `member`
      (is (= #{"m-op"}  (names db (q 'member  "m-mod"))) "m-mod members m-op (via :exposes)")
      (is (= #{"m-mod"} (names db (q 'member  "m-sub"))) "m-sub members m-mod (via :child)")
      ;; transitive membership (the rollup) reaches the op from the subsystem
      (is (= #{"m-op" "m-mod"} (names db (q 'member+ "m-sub"))) "m-sub member+ reaches both"))))

(deftest via-member-lands-effectful-at-module-and-subsystem
  (testing "(via :member Scope effectful) rolls the effectful property up the membership ladder"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])]
      (is (= #{"m-mod"} (names db (lens/focus-nodes db '[(via :member Module effectful)])))
          "the module that contains a directly-effectful op")
      (is (= #{"m-sub"} (names db (lens/focus-nodes db '[(via :member Subsystem effectful)])))
          "the subsystem that transitively contains a directly-effectful op"))))

(deftest in-module-is-derived-from-member-and-substrate-names-no-vocab
  (testing "in-module still resolves (now via member), and substrate-rules names no vocab relation"
    (let [db (build/vars->cozo [#'mc-op #'mc-mod #'mc-sub])]
      (is (= #{"m-mod"} (set (cq/q '[:find [?mn ...] :in $ % :where [?o :entity/name "m-op"] (in-module ?o ?mn)]
                                   db (s/vocab-rules))))
          "m-op is in module m-mod (via the derived in-module)"))
    ;; the de-leak itself: substrate-rules must not name child/exposes/owns
    (let [pr (pr-str fukan.canvas.core.rules/substrate-rules)]
      (is (not (or (re-find #":child" pr) (re-find #":exposes" pr) (re-find #":owns" pr)))
          "substrate-rules names no code-vocab relation kind"))))
