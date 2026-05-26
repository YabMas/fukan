(ns fukan.canvas.core.defquery-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.defquery :as dq]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]))

(deftest primitive-name-expansion
  (testing "(Module ?x) expands to [?x :entity/type :Module]"
    (is (= '[[?x :entity/type :Module]]
           (dq/expand '[(Module ?x)])))))

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
  (testing "(this :mod/entity ?v) expands to four datom clauses binding ?v"
    (let [expanded (dq/expand '[(this :infra.server/start_server ?e)])]
      (is (= 4 (count expanded)) "should produce four datom clauses")
      ;; Third clause: [<mod-var> :module/child ?e]
      (is (= :module/child (second (nth expanded 2))))
      (is (= '?e (nth (nth expanded 2) 2)))
      ;; Fourth clause: [?e :entity/name "start_server"]
      (is (= '?e (first (nth expanded 3))))
      (is (= :entity/name (second (nth expanded 3))))
      (is (= "start_server" (nth (nth expanded 3) 2))))))

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
            query    (into [:find '?n :where] expanded)
            results  (d/q query db)]
        (is (= #{["start_server"]} results)
            "query should find the entity named start_server")))))
