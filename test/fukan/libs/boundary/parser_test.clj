(ns fukan.libs.boundary.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.libs.boundary.parser :as parser]
            [instaparse.core :as insta]))

(defn- parse [text]
  (parser/parse-boundary text))

(deftest header-parses
  (testing "the version header is recognised and surfaced on the result"
    (let [result (parse "-- boundary: 1\n")]
      (is (not (insta/failure? result)) "parse produced an AST, not a failure")
      (is (= 1 (:boundary-version result))))))

(deftest header-required
  (testing "a file without the header fails to parse"
    (let [result (parse "use \"x.allium\" as x\n")]
      (is (insta/failure? result) "expected failure"))))

(deftest use-decl-basic
  (testing "a single-segment use declaration parses"
    (let [result (parse "-- boundary: 1\nuse \"x.allium\" as x\n")]
      (is (= [{:type :use :path "x.allium" :alias "x"}]
             (:declarations result))))))

(deftest use-decl-relative
  (testing "relative paths are preserved verbatim in :path"
    (let [result (parse "-- boundary: 1\nuse \"../model/spec.allium\" as model\n")]
      (is (= [{:type :use :path "../model/spec.allium" :alias "model"}]
             (:declarations result))))))

(deftest use-decl-multiple
  (testing "multiple use declarations parse in order"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"a.allium\" as a\n"
                             "use \"b.allium\" as b\n"
                             "use \"c.allium\" as c\n"))]
      (is (= [{:type :use :path "a.allium" :alias "a"}
              {:type :use :path "b.allium" :alias "b"}
              {:type :use :path "c.allium" :alias "c"}]
             (:declarations result))))))
