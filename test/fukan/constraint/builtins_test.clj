(ns fukan.constraint.builtins-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.builtins :as b]))

(deftest in-set-membership
  (is (b/in? "a" #{"a" "b"}))
  (is (not (b/in? "c" #{"a" "b"}))))

(deftest contains-substring
  (is (b/contains? "fukan/web/views" "web"))
  (is (not (b/contains? "fukan/infra" "web"))))

(deftest is-present-non-nil
  (is (b/is-present? "x"))
  (is (b/is-present? 0))
  (is (not (b/is-present? nil))))

(deftest is-absent-nil-only
  (is (b/is-absent? nil))
  (is (not (b/is-absent? false))))
