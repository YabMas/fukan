(ns fukan.canvas.core.helpers-test
  (:require [clojure.test :refer [deftest is testing]]
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
