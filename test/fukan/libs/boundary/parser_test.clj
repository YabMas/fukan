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
