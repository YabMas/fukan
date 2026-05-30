(ns fukan.canvas.core.defquery-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.core.defquery :as dq]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]))

(deftest primitive-name-expansion
  (testing "(Module ?x) expands to a family-of rule call + family predicate"
    (let [expanded (dq/expand '[(Module ?x)])
          [kind-clause pred-clause] expanded]
      (is (= 2 (count expanded)))
      (is (= 'family-of (first kind-clause)) "first clause is the family-of rule")
      (is (= '?x (second kind-clause)))
      (let [fam-var (nth kind-clause 2)]
        (is (= ['= fam-var :family/module] (first pred-clause))
            "predicate binds the family var to :family/module")))))

(deftest tag-name-expansion
  (testing "(tag :X ?e) expands to [?e :entity/tag :X]"
    (is (= '[[?e :entity/tag :X]]
           (dq/expand '[(tag :X ?e)])))))

(deftest defquery-registers-and-expands
  (testing "a defquery'd operator expands into its body"
    (dq/defquery test-op [?x ?y]
      "Test operator."
      '[[?x :foo ?y]])
    (is (= '[[?a :foo ?b]]
           (dq/expand '[(test-op ?a ?b)])))))

(deftest recursive-expansion
  (testing "defquery bodies can reference other defquery'd operators"
    (dq/defquery base-op [?x]
      "base"
      '[[?x :entity/type :Module]])
    (dq/defquery outer-op [?x]
      "outer"
      '[(base-op ?x) [?x :entity/name ?n]])
    (is (= '[[?a :entity/type :Module] [?a :entity/name ?n]]
           (dq/expand '[(outer-op ?a)])))))

(deftest this-expansion-shape
  (testing "(this :mod/entity ?v) expands to a module kind-of check + 3 clauses"
    (let [expanded (dq/expand '[(this :infra.server/start_server ?e)])]
      (is (= 5 (count expanded)) "family-of + family predicate + 3 datom clauses")
      ;; Clause 0: (family-of <mod-var> <fam-var>)
      (is (= 'family-of (first (nth expanded 0))))
      ;; Clause 3: [<mod-var> :module/child ?e]
      (is (= :module/child (second (nth expanded 3))))
      (is (= '?e (nth (nth expanded 3) 2)))
      ;; Clause 4: [?e :entity/name "start_server"]
      (is (= '?e (first (nth expanded 4))))
      (is (= :entity/name (second (nth expanded 4))))
      (is (= "start_server" (nth (nth expanded 4) 2))))))

(deftest this-resolves-namespaced-keyword
  (testing "(this :module/name ?var) binds ?var to the entity in that module"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (function "start_server"
                   "Start the HTTP server."
                   (takes [opts :ServerOpts])
                   (gives :Unit))))]
      (let [expanded (dq/expand '[(this :infra.server/start_server ?a)
                                  [?a :entity/name ?n]])
            query    (into [:find '?n :in '$ '% :where] expanded)
            results  (d/q query db classification/rules)]
        (is (= #{["start_server"]} results)
            "query should find the entity named start_server")))))
