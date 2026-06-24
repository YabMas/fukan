(ns fukan.cozo.query-test
  "The general datalog→CozoScript query compiler (`q`) + entity accessor — must agree with
   datascript's `d/q` / `d/entity` on the real model across the find/in/where forms fukan
   uses (relation + collection finds, `$ %` rules, `$ ?param` scalars, entity attribute
   resolution). Results are STRINGS over the triple view, so the comparison stringifies the
   datascript side; `entity` resolves typed values from the typed buckets."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.query :as q]
            [canvas.vocab.code.module :as module]))

(def ^:private MOD-NAMES
  '[:find ?n :where [?m :structure/of :canvas.vocab.code.module/Module] [?m :entity/name ?n]])

(deftest q-agrees-with-datascript
  (let [ds  (pipeline/build-model "src")
        cdb (mirror/mirror ds)]
    (try
      (testing "relation find — module names"
        (is (= (set (map (comp str first) (d/q MOD-NAMES ds)))
               (set (map first (q/q MOD-NAMES cdb))))))
      (testing "collection find — [?n ...]"
        (is (= (set (map (comp str first) (d/q MOD-NAMES ds)))
               (set (q/q '[:find [?n ...]
                           :where [?m :structure/of :canvas.vocab.code.module/Module] [?m :entity/name ?n]]
                         cdb)))))
      (testing "rules query — module-depends over $ %"
        (let [rels module/module-depends-rules
              dq   '[:find ?mn ?nn :in $ % :where (module-depends ?m ?n) [?m :entity/name ?mn] [?n :entity/name ?nn]]]
          (is (= (set (d/q dq ds rels)) (set (q/q dq cdb rels))))))
      (testing "scalar param query — $ ?from (an eid)"
        (let [eid (ffirst (d/q '[:find ?e :where [?e :entity/name "module-corresponds?"]
                                 [?e :structure/of :canvas.vocab.code.operation/Operation] [?e :val/extracted true]] ds))
              dq  '[:find ?kn :in $ ?from :where [?r :rel/from ?from] [?r :rel/kind :performs]
                    [?r :rel/to ?to] [?to :val/name ?kn]]]
          (is (= (set (map first (d/q dq ds eid)))
                 (set (map first (q/q dq cdb (str eid))))))))
      (finally (db/close cdb)))))

(deftest wildcard-underscore-compiles
  ;; `_` is a discard placeholder (any value, don't bind). Cozo has no wildcard, so each `_` must
  ;; become a UNIQUE fresh var — reusing one would wrongly join the positions. Two `_`s in one query
  ;; (different attrs) must NOT constrain each other. The lens selection queries rely on this.
  (let [ds  (pipeline/build-model "src")
        cdb (mirror/mirror ds)]
    (try
      (testing "single `_` — every node with any :structure/of"
        (let [q '[:find [?n ...] :where [?n :structure/of _]]]
          (is (= (set (map str (d/q q ds))) (set (q/q q cdb))))))
      (testing "two independent `_`s do not cross-join"
        (let [q '[:find [?n ...] :where [?n :structure/of _] [?r :rel/from ?n] [?r :rel/to _]]]
          (is (= (set (map str (d/q q ds))) (set (q/q q cdb))))))
      (finally (db/close cdb)))))

(deftest scalar-param-does-not-leak-into-rules
  ;; regression: a `$ % ?op` scalar must substitute only into the WHERE body, never into a `%`
  ;; rule whose head var shares the name (`?op`). Inlining there corrupted the rule head
  ;; (`r_reaches_effect[604, en]`). The recursive reaches-effect rule is the real-world trigger.
  (let [ds  (pipeline/build-model "src")
        cdb (mirror/mirror ds)]
    (try
      (let [eid   (ffirst (d/q '[:find ?e :where [?e :entity/name "check"]
                                 [?e :structure/of :canvas.vocab.code.operation/Operation] [?e :val/extracted true]] ds))
            rules '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
                    [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]]
            dq    '[:find [?en ...] :in $ % ?op :where (reaches-effect ?op ?en)]]
        (is (= (set (d/q dq ds rules eid))
               (set (q/q dq cdb rules (str eid))))
            "the scalar binds the where-body's ?op; the rule's head ?op stays a variable"))
      (finally (db/close cdb)))))

(deftest entity-agrees-with-datascript
  (let [ds  (pipeline/build-model "src")
        cdb (mirror/mirror ds)]
    (try
      (testing "entity resolves an op's attributes with real types"
        (let [eid (ffirst (d/q '[:find ?e :where [?e :entity/name "check"]
                                 [?e :structure/of :canvas.vocab.code.operation/Operation] [?e :val/extracted true]] ds))
              de  (d/entity ds eid)
              ce  (q/entity cdb (str eid))]
          (is (= (:entity/name de) (:entity/name ce)) "name")
          (is (= (:val/extracted de) (:val/extracted ce)) "extracted (a typed boolean)")
          (is (= (:val/private de) (:val/private ce)) "private (a typed boolean)")))
      (testing "entity returns nil for an unknown eid"
        (is (nil? (q/entity cdb "999999"))))
      (finally (db/close cdb)))))
