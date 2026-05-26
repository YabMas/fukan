(ns fukan.canvas.core.helpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]))

(deftest arrow-shape
  (testing "arrow combinator constructs a function-typed shape"
    (let [shape (h/arrow {:email :String} :Unit)]
      (is (= {:kind :arrow :inputs {:email :String} :outputs :Unit} shape)))))

(deftest record-of
  (testing "record-of constructs a record type expression from field pairs"
    (let [r (h/record-of [[:email :String] [:password :String]])]
      (is (= {:kind :record :fields [[:email :String] [:password :String]]} r)))))

(deftest scoped-canvas-build
  (testing "with-canvas wraps construction in a store binding"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (h/declare-affordance "create"
                   :shape (h/arrow (h/record-of [[:email :String]]) :Unit)
                   :role :exposed-call)))]
      (is (= 1 (count (store/all-modules db)))))))

(deftest declare-affordance-registers-module-child
  (testing "declare-affordance inside within-module adds :module/child datom on the Module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (h/declare-affordance "create" :role :exposed-call)))
          mid (ffirst (clojure.core/or
                        (seq (d/q
                               '[:find ?id :where [?e :entity/type :Module] [?e :entity/id ?id]]
                               db))
                        nil))]
      (is (= 1 (count (store/affordances-in db mid)))))))

(deftest declare-type-registers-module-child
  (testing "declare-type inside within-module produces a Type owned by the Module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (h/declare-type "Account" :kind :record :fields [["email" {:kind :atomic :name :String}]] :doc "An account.")))
          mid (ffirst (d/q
                        '[:find ?id :where [?e :entity/type :Module] [?e :entity/id ?id]]
                        db))]
      (is (= #{[:Type "Account"]} (set (store/children-of-module db mid)))))))

(deftest declare-state-registers-module-child
  (testing "declare-state inside within-module adds :module/child datom for the State"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (h/declare-state "current-user" :shape {:kind :atomic :name :String})))
          mid (ffirst (d/q
                        '[:find ?id :where [?e :entity/type :Module] [?e :entity/id ?id]]
                        db))]
      (is (= #{[:State "current-user"]} (set (store/children-of-module db mid)))))))
